package building;

import building.state.Direction;
import building.state.State;
import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import util.interrupt.Interruptible;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class Controller implements Runnable, Interruptible {
    @Setter
    private List<Elevator> elevators;
    private final Queue<Call> calls;

    private final Condition controllerStopCondition;
    private final Lock callLock;
    private final Lock elevatorLock;

    @Getter
    private volatile boolean isRunning;

    private Controller() {
        this.elevators = new ArrayList<>();
        this.calls = new LinkedList<>();
        this.callLock = new ReentrantLock(true);
        this.elevatorLock = new ReentrantLock(true);
        this.controllerStopCondition = callLock.newCondition();
        this.isRunning = false;
    }

    public static Controller of(List<Elevator> elevators) {
        checkNotNull(elevators);

        Controller controller = new Controller();
        controller.setElevators(elevators);

        return controller;
    }

    public static Controller getEmpty() {
        return new Controller();
    }

    public boolean canCallElevator(Call call) {
        checkNotNull(call);

        elevatorLock.lock();
        boolean result = elevators.stream()
                .noneMatch(i -> (i.getDirection().equals(call.getDirection()) || i.getDirection().equals(Direction.NONE))
                        && i.getCurrentFloorNumber() == call.getTargetFloorNumber()
                        && (i.getState().equals(State.LOAD) || i.getState().equals(State.OPEN_DOOR)));
        elevatorLock.unlock();

        return result;
    }

    public void addCall(Call call) {
        checkNotNull(call);
        checkArgument(call.getTargetFloorNumber() >= Floor.GROUND_FLOOR);

        callLock.lock();
        calls.add(call);
        controllerStopCondition.signal();
        callLock.unlock();

        log.info("call added: {}", call.getTargetFloorNumber());
    }

    public void removeCall(Call call) {
        checkNotNull(call);

        callLock.lock();
        calls.removeAll(calls.stream().filter(call::equals).collect(Collectors.toList()));
        callLock.unlock();

        log.info("call has been removed {}", call);
    }

    public void dispatchCall() {
        callLock.lock();

        if (!calls.isEmpty()) {
            Call call = calls.poll();

            List<Elevator> suitableElevators;

            elevatorLock.lock();
            suitableElevators = elevators.stream()
                    .filter(i -> i.getDirection().equals(Direction.NONE)
                            && i.getState().equals(State.STOP))
                    .sorted(Comparator.comparing(i -> Math.abs(i.getCurrentFloorNumber() - call.getTargetFloorNumber())))
                    .collect(Collectors.toList());
            elevatorLock.unlock();

            if (!suitableElevators.isEmpty()) {
                suitableElevators.get(0).addCall(call);
                log.info("call has been dispatched {}", call);
            } else {
                calls.add(call);
            }
        }

        callLock.unlock();
    }

    public void waitCall() {
        callLock.lock();
        while (calls.isEmpty()) {
            try {
                controllerStopCondition.await();
            } catch (InterruptedException exception) {
                log.error("controller cannot wait, cause it was interrupted");
                log.error(exception.getMessage());

                Thread.currentThread().interrupt();
            }
        }
        callLock.unlock();
    }

    public List<Call> getAllCalls() {
        callLock.lock();
        List<Call> allCalls = ImmutableList.copyOf(calls);
        callLock.unlock();

        return allCalls;
    }

    public void turnOff() {
        isRunning = false;

        log.info("controller has been stopped");
    }

    public void turnOn() {
        isRunning = true;

        log.info("controller has been started");
    }

    @Override
    public void run() {
        turnOn();

        while (isRunning) {
            while (calls.isEmpty()) {
                waitCall();
            }
            dispatchCall();
        }
    }
}
