package building;

import building.state.Direction;
import building.state.State;
import com.google.common.collect.ImmutableList;
import human.Human;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import util.StatisticsHolder;
import util.interrupt.Interruptible;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.*;

@Slf4j
public class Elevator implements Runnable, Interruptible {
    public static final int MIN_CAPACITY = 0;

    @Getter
    private final UUID id;
    @Getter
    private final int capacity;
    @Getter
    private final int moveSpeed;
    @Getter
    private final int doorWorkSpeed;
    private final List<Human> passengers;
    private final List<Call> calls;

    private final Condition elevatorStopCondition;
    private final Lock currentFloorLock;
    private final Lock peopleLock;
    private final Lock stateLock;
    private final Lock callLock;

    @Getter
    private final AtomicInteger numberOfDeliveredPeople;
    private final AtomicInteger currentFloorNumber;
    private volatile boolean isRunning;
    private volatile Building building;
    private volatile Direction direction;
    private volatile State state;

    private Elevator(int capacity, int currentFloorNumber, int moveSpeed, int doorWorkSpeed) {
        checkArgument(capacity > MIN_CAPACITY);
        checkArgument(moveSpeed >= MIN_SPEED && moveSpeed <= MAX_SPEED);
        checkArgument(doorWorkSpeed >= MIN_SPEED && doorWorkSpeed <= MAX_SPEED);

        this.id = UUID.randomUUID();
        this.capacity = capacity;
        this.moveSpeed = moveSpeed;
        this.doorWorkSpeed = doorWorkSpeed;
        this.currentFloorNumber = new AtomicInteger(currentFloorNumber);

        this.currentFloorLock = new ReentrantLock(true);
        this.peopleLock = new ReentrantLock(true);
        this.stateLock = new ReentrantLock(true);
        this.callLock = new ReentrantLock(true);
        this.elevatorStopCondition = callLock.newCondition();

        this.passengers = new ArrayList<>();
        this.calls = new ArrayList<>();

        this.direction = Direction.NONE;
        this.state = State.STOP;

        this.numberOfDeliveredPeople = new AtomicInteger(0);
    }

    public static Elevator of(int capacity) {
        return new Elevator(capacity, Floor.GROUND_FLOOR, MIN_SPEED, MIN_SPEED);
    }

    public static Elevator of(int capacity, int startFloorNumber) {
        return new Elevator(capacity, startFloorNumber, MIN_SPEED, MIN_SPEED);
    }

    public static Elevator of(int capacity, int startFloorNumber, int speed) {
        return new Elevator(capacity, startFloorNumber, speed, speed);
    }

    public static Elevator of(int capacity, int startFloorNumber, int moveSpeed, int doorWorkSpeed) {
        return new Elevator(capacity, startFloorNumber, moveSpeed, doorWorkSpeed);
    }

    public static Elevator of(int capacity, Floor startFloor) {
        checkNotNull(startFloor);

        return new Elevator(capacity, startFloor.getFloorNumber(), MIN_SPEED, MIN_SPEED);
    }

    public static Elevator of(int capacity, Floor startFloor, int speed) {
        checkNotNull(startFloor);

        return new Elevator(capacity, startFloor.getFloorNumber(), speed, speed);
    }

    public static Elevator of(int capacity, Floor startFloor, int moveSpeed, int doorWorkSpeed) {
        checkNotNull(startFloor);

        return new Elevator(capacity, startFloor.getFloorNumber(), moveSpeed, doorWorkSpeed);
    }

    public void addTo(Building building) {
        checkNotNull(building);

        this.building = building;
    }

    public int getCurrentFloorNumber() {
        currentFloorLock.lock();
        int floor = currentFloorNumber.get();
        currentFloorLock.unlock();

        return floor;
    }

    public Floor getCurrentFloor() {
        currentFloorLock.lock();
        Floor floor = building.getFloor(currentFloorNumber.get());
        currentFloorLock.unlock();

        return floor;
    }

    public Controller getController() {
        checkNotNull(building);
        checkNotNull(building.getController());

        return building.getController();
    }

    public int getNumberOfPeople() {
        peopleLock.lock();
        int size = passengers.size();
        peopleLock.unlock();

        return size;
    }

    public State getState() {
        stateLock.lock();
        State currentState = this.state;
        stateLock.unlock();

        return currentState;
    }

    public Direction getDirection() {
        stateLock.lock();
        Direction currentDirection = this.direction;
        stateLock.unlock();

        return currentDirection;
    }

    public Direction getDestinationDirection() {
        callLock.lock();
        stateLock.lock();
        Direction currentDirection = calls.isEmpty() ? Direction.NONE : calls.get(0).getDirection();
        stateLock.unlock();
        callLock.unlock();

        return currentDirection;
    }

    public int getFreeSpace() {
        peopleLock.lock();
        int engagedSpace = passengers.stream().mapToInt(Human::getWeight).sum();
        peopleLock.unlock();

        return capacity - engagedSpace;
    }

    public boolean isRunning() {
        stateLock.lock();
        boolean result = isRunning;
        stateLock.unlock();

        return result;
    }

    public List<Human> getPassengers() {
        peopleLock.lock();
        List<Human> list = ImmutableList.copyOf(passengers);
        peopleLock.unlock();

        return list;
    }

    public List<Call> getCalls() {
        callLock.lock();
        List<Call> list = ImmutableList.copyOf(calls);
        callLock.unlock();

        return list;
    }

    public void addCall(Call call) {
        checkNotNull(call);

        callLock.lock();
        calls.add(call);
        elevatorStopCondition.signal();
        callLock.unlock();

        stateLock.lock();
        currentFloorLock.lock();
        if (direction == Direction.NONE) {
            direction = call.getTargetFloorNumber() - currentFloorNumber.get() > 0 ? Direction.UP : Direction.DOWN;
        }
        currentFloorLock.unlock();
        stateLock.unlock();

        log.info("elevator called to {}", call);
    }

    public void goUp() {
        checkState(getCurrentFloorNumber() < building.getNumberOfFloors());

        stateLock.lock();
        direction = Direction.UP;
        state = State.MOVE;
        stateLock.unlock();

        currentFloorLock.lock();
        currentFloorNumber.incrementAndGet();
        currentFloorLock.unlock();

        StatisticsHolder.getInstance().incrementNumberOfPassedFloors();

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - moveSpeed);
        } catch (InterruptedException exception){
            log.error("elevator cannot go up, cause it was interrupted");
            log.error(exception.getMessage());

            end();
            turnOff();
            Thread.currentThread().interrupt();
        }

        log.info("elevator moved to floor number {}", currentFloorNumber);
    }

    public void goDown() {
        checkState(currentFloorNumber.get() > Floor.GROUND_FLOOR);

        stateLock.lock();
        direction = Direction.DOWN;
        state = State.MOVE;
        stateLock.unlock();

        currentFloorLock.lock();
        currentFloorNumber.decrementAndGet();
        currentFloorLock.unlock();

        StatisticsHolder.getInstance().incrementNumberOfPassedFloors();

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - moveSpeed);
        } catch (InterruptedException exception){
            log.error("elevator cannot go down, cause it was interrupted");
            log.error(exception.getMessage());

            end();
            turnOff();
            Thread.currentThread().interrupt();
        }

        log.info("elevator moved to floor number {}", currentFloorNumber);
    }

    public void openDoor() {
        stateLock.lock();
        state = State.OPEN_DOOR;
        stateLock.unlock();

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - doorWorkSpeed);
        } catch (InterruptedException exception){
            log.error("elevator cannot open door, cause it was interrupted");
            log.error(exception.getMessage());

            end();
            turnOff();
            Thread.currentThread().interrupt();
        }

        log.info("elevator has opened his door");
    }

    public void pickUpHuman(Human human) {
        checkNotNull(human);

        stateLock.lock();
        if (direction == Direction.NONE) {
            direction = human.getCall().getDirection();
        }
        stateLock.unlock();

        peopleLock.lock();
        passengers.add(human);
        peopleLock.unlock();

        getController().removeCall(Call.of(getCurrentFloorNumber(), human.getCall().getDirection()));

        addCall(human.getCall());

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - doorWorkSpeed);
        } catch (InterruptedException exception){
            log.error("elevator cannot pickup human, cause it was interrupted");
            log.error(exception.getMessage());

            end();
            turnOff();
            Thread.currentThread().interrupt();
        }

        log.info("elevator pick up the next human: {}", human);
    }

    public void disembark(Human human) {
        checkNotNull(human);
        checkArgument(passengers.contains(human));

        peopleLock.lock();
        passengers.remove(human);
        peopleLock.unlock();

        StatisticsHolder.getInstance().incrementNumberOfDeliveredPeople();
        numberOfDeliveredPeople.incrementAndGet();

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - doorWorkSpeed);
        } catch (InterruptedException exception){
            log.error("elevator cannot disembark human, cause it was interrupted");
            log.error(exception.getMessage());

            end();
            turnOff();
            Thread.currentThread().interrupt();
        }

        log.info("elevator disembark the next human: {}", human);
    }

    public boolean checkFloor() {
        Human human = null;
        boolean result = false;

        getCurrentFloor().getFloorLock().lock();
        stateLock.lock();
        Direction destinationDirection = getDestinationDirection();
        if (!destinationDirection.equals(Direction.NONE) && destinationDirection.equals(direction)) {
            human = getCurrentFloor().getFirstHuman(direction);
        }
        stateLock.unlock();
        getCurrentFloor().getFloorLock().unlock();

        peopleLock.lock();
        if (human != null && human.getWeight() <= getFreeSpace()) {
            stateLock.lock();
            result = human.getCall().getDirection() == direction;
            stateLock.unlock();
        }
        peopleLock.unlock();

        return result;
    }

    public void load() {
        stateLock.lock();
        state = State.LOAD;
        stateLock.unlock();

        handleDisembark();
        handleLoadDirectionState();
        handleEmbark();

        log.info("elevator finishes load");
    }

    private void handleDisembark() {
        peopleLock.lock();
        List<Human> peopleForDisembark = passengers.stream()
                .filter(i -> i.getCall().getTargetFloorNumber() == currentFloorNumber.get())
                .collect(Collectors.toList());
        peopleLock.unlock();

        peopleForDisembark.forEach(this::disembark);

        log.info("elevator has finished disembarking");
    }

    private void handleLoadDirectionState() {
        peopleLock.lock();
        stateLock.lock();
        if (passengers.isEmpty() && calls.isEmpty()) {
            log.info("elevator is empty");
            direction = Direction.NONE;
        } else if (passengers.isEmpty()) {
            direction = getDestinationDirection();
        }
        stateLock.unlock();
        peopleLock.unlock();
    }

    private void handleEmbark() {
        boolean isEmbarking = true;
        while (state == State.LOAD && isEmbarking) {
            getCurrentFloor().getFloorLock().lock();
            stateLock.lock();
            Human human = getCurrentFloor().getFirstHuman(direction);
            Direction destinationDirection = getDestinationDirection();

            if (human != null && ((!destinationDirection.equals(Direction.NONE)
                    && destinationDirection.equals(human.getCall().getDirection()))
                    || (destinationDirection.equals(Direction.NONE)
                    && human.getCall().getDirection().equals(direction))
                    || direction.equals(Direction.NONE))) {

                if (human.getWeight() <= getFreeSpace()) {
                    if (direction.equals(Direction.NONE)) {
                        direction = human.getCall().getDirection();
                    }
                    stateLock.unlock();
                    human = getCurrentFloor().pollFirstHuman(direction);
                    getCurrentFloor().getFloorLock().unlock();
                    pickUpHuman(human);

                    log.info("human has been picked up {}", human);
                } else {
                    stateLock.unlock();
                    getCurrentFloor().getFloorLock().unlock();
                    getController().addCall(Call.of(currentFloorNumber.get(), human.getCall().getDirection()));

                    log.info("elevator cannot pick up human, 'cause there is not enough space {}", human);
                    log.info("elevator recall {}", human.getCall());

                    isEmbarking = false;
                }
            } else {
                stateLock.unlock();
                getCurrentFloor().getFloorLock().unlock();

                isEmbarking = false;
            }
        }
    }

    public void closeDoor() {
        stateLock.lock();
        state = State.CLOSE_DOOR;
        stateLock.unlock();

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - doorWorkSpeed);
        } catch (InterruptedException exception){
            log.error("elevator cannot close door, cause it was interrupted");
            log.error(exception.getMessage());

            end();
            turnOff();
            Thread.currentThread().interrupt();
        }

        log.info("elevator has closed his door");
    }

    public void stop() {
        callLock.lock();

        stateLock.lock();
        direction = Direction.NONE;
        state = State.STOP;
        stateLock.unlock();

        while (calls.isEmpty()) {
            log.info("elevator stopped");

            try {
                elevatorStopCondition.await();
            } catch (InterruptedException exception){
                log.error("elevator cannot be stopped, cause it was interrupted");
                log.error(exception.getMessage());

                end();
                turnOff();
                Thread.currentThread().interrupt();
            }
        }

        callLock.unlock();
    }

    public void end() {
        stateLock.lock();
        direction = Direction.NONE;
        state = State.END;
        stateLock.unlock();

        log.warn("elevator has finished his way");
    }

    public boolean removeExecutedCalls() {
        boolean hasExecutedCalls;

        callLock.lock();
        List<Call> currentFloorCalls = calls.stream()
                .filter(i -> i.getTargetFloorNumber() == currentFloorNumber.get())
                .collect(Collectors.toList());
        hasExecutedCalls = calls.removeAll(currentFloorCalls);
        callLock.unlock();

        return hasExecutedCalls;
    }

    @Override
    public void turnOff() {
        isRunning = false;

        log.info("elevator has been stopped");
    }

    @Override
    public void turnOn() {
        isRunning = true;

        log.info("elevator has been started");
    }

    @Override
    public void run() {
        boolean areWaitingPeopleOnThisFloor;
        boolean hasExecutedCalls;
        int currentCallFloorNumber;

        turnOn();

        while (isRunning) {
            callLock.lock();
            if (calls.isEmpty()) {
                callLock.unlock();
                stop();
            } else {
                hasExecutedCalls = removeExecutedCalls();
                currentCallFloorNumber = calls.isEmpty()
                        ? currentFloorNumber.get()
                        : calls.get(0).getTargetFloorNumber();

                callLock.unlock();

                areWaitingPeopleOnThisFloor = checkFloor();

                if (hasExecutedCalls || areWaitingPeopleOnThisFloor) {
                    openDoor();
                    load();
                    closeDoor();
                } else if (currentCallFloorNumber > currentFloorNumber.get()) {
                    goUp();
                } else if (currentCallFloorNumber < currentFloorNumber.get()) {
                    goDown();
                }
            }
        }

        turnOff();
        end();

    }

    @Override
    public String toString() {
        return String.format("State: %s; Direction: %s; Free space: %s; PeopleDelivered: %d; Calls: %s; Passengers: %s; "
                , getState(), getDirection(), getFreeSpace(), numberOfDeliveredPeople.get(), getCalls(), getPassengers());
    }
}
