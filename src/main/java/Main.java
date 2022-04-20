import building.Building;
import building.Controller;
import building.Elevator;
import human.Human;
import util.HumanGenerator;
import util.UserInterface;

public class Main {
    public static void main(String[] args) {
        int numberOfFloors = 10;
        int capacityOfElevator = 500;
        int startFloorNumber = 0;
        int movingSpeed = 100;
        int doorWorkSpeed = 100;
        int generatingSpeed = 100;
        int userInterfaceRenderingSpeed = 600;

        Building building = Building.of(numberOfFloors)
                .setController(Controller.getEmpty())
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed))
                .addElevator(Elevator.of(capacityOfElevator, startFloorNumber, movingSpeed, doorWorkSpeed));

        HumanGenerator humanGenerator = HumanGenerator.of(building,
                Human.MIN_WEIGHT, Human.MAX_WEIGHT, generatingSpeed);

        UserInterface userInterface = UserInterface.of(building, userInterfaceRenderingSpeed);

        humanGenerator.start();
        userInterface.start();
        building.start();
    }
}
