package util;

import building.Building;
import building.Floor;
import human.Human;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import util.interrupt.Interruptible;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class HumanGenerator extends Thread implements Interruptible {
    private final Building building;
    private final int generateSpeed;
    private final int weightFrom;
    private final int weightTo;

    @Getter
    public boolean isRunning;

    private final Random random;

    private HumanGenerator(Building building, int weightFrom, int weightTo, int generateSpeed) {
        checkArgument(generateSpeed >= MIN_SPEED && generateSpeed <= MAX_SPEED);
        checkArgument(weightFrom >= Human.MIN_WEIGHT);
        checkArgument(weightTo <= Human.MAX_WEIGHT);
        checkArgument(weightTo >= weightFrom);
        checkNotNull(building);

        this.generateSpeed = generateSpeed;
        this.weightFrom = weightFrom;
        this.building = building;
        this.weightTo = weightTo;
        this.random = new Random();

        String threadName = "humanGenerator";
        this.setName(threadName);
    }

    public static HumanGenerator of(Building building, int weightFrom, int weightTo, int generateSpeed) {
        return new HumanGenerator(building, weightFrom, weightTo, generateSpeed);
    }

    public static HumanGenerator of(Building building, int weightFrom, int weightTo) {
        return new HumanGenerator(building, weightFrom, weightTo, MIN_SPEED);
    }

    public static HumanGenerator of(Building building) {
        return new HumanGenerator(building, Human.MIN_WEIGHT, Human.MAX_WEIGHT, MIN_SPEED);
    }

    public void generate() {
        Floor floor = building.getFloor(Math.abs(random.nextInt()) % building.getNumberOfFloors());
        int weight = Math.abs(random.nextInt()) % (weightTo - weightFrom) + weightFrom;
        int targetFloor;

        do {
            targetFloor = Math.abs(random.nextInt()) % building.getNumberOfFloors();
        } while (targetFloor == floor.getFloorNumber());

        floor.addHuman(Human.of(weight, targetFloor, floor));

        StatisticsHolder.getInstance().incrementNumberOfGeneratedPeople();

        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_OPERATION_TIME - generateSpeed);
        } catch (InterruptedException exception) {
            log.error("human generator has been interrupted");
            log.error(exception.getMessage());

            Thread.currentThread().interrupt();
        }

        log.info("human has been generated at {}", targetFloor);
    }

    public void turnOff() {
        isRunning = false;
    }

    public void turnOn() {
        isRunning = true;
    }

    @Override
    public void run() {
        turnOn();
        while (isRunning && !isInterrupted()) {
            generate();
        }
    }
}
