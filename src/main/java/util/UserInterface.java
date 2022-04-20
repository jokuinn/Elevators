package util;

import building.Building;
import building.state.Direction;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import util.interrupt.Interruptible;

import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class UserInterface extends Thread implements Interruptible {
    private final Building building;
    private final int renderingSpeed;
    @Getter
    private boolean isRunning;

    private UserInterface(Building building, int renderingSpeed) {
        checkNotNull(building);
        checkArgument(renderingSpeed <= MAX_SPEED && renderingSpeed >= MIN_SPEED);

        this.building = building;
        this.renderingSpeed = renderingSpeed;

        String threadName = "userInterface";
        this.setName(threadName);
    }

    public static UserInterface of(Building building, int renderingSpeed) {
        return new UserInterface(building, renderingSpeed);
    }

    public void printBuilding() {
        System.out.print("\033[H\033[2J");
        System.out.flush();

        String color;
        System.out.printf("Delivered: %s\n", StatisticsHolder.getInstance().getNumberOfDeliveredPeople());
        System.out.printf("Generated: %s\n", StatisticsHolder.getInstance().getNumberOfGeneratedPeople());
        System.out.printf("Floors passed: %s\n", StatisticsHolder.getInstance().getNumberOfPassedFloors());

        System.out.println(building.getController().getAllCalls());
        for (int i = 0; i < building.getElevators().size(); i++) {
            System.out.println(i + " " + building.getElevators().get(i).toString());
        }
    }

    @Override
    public void turnOff() {
        isRunning = false;
    }

    @Override
    public void turnOn() {
        isRunning = true;
    }

    @Override
    public void run() {
        turnOn();
        while (isRunning && !isInterrupted()) {
            waitForOperation();
            printBuilding();
        }
    }

    private void waitForOperation() {
        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - renderingSpeed);
        } catch (InterruptedException exception) {
            log.error("user interface cannot wait, cause it was interrupted");
            log.error(exception.getMessage());

            Thread.currentThread().interrupt();
        }
    }
}