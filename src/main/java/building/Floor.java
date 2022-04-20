package building;

import building.state.Direction;
import com.google.common.collect.ImmutableList;
import human.Human;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@EqualsAndHashCode
public class Floor {
    public static final int GROUND_FLOOR = 0;

    @Getter
    private final int floorNumber;
    @Getter
    private final Lock floorLock;
    @Getter
    private final Condition floorLockCondition;
    private final Building building;
    private final Queue<Human> queueUp;
    private final Queue<Human> queueDown;

    private Floor(int floorNumber, Building building) {
        checkArgument(floorNumber >= GROUND_FLOOR);
        checkArgument(floorNumber < building.getNumberOfFloors());
        checkNotNull(building);

        this.floorNumber = floorNumber;
        this.building = building;
        this.queueUp = new ConcurrentLinkedQueue<>();
        this.queueDown = new ConcurrentLinkedQueue<>();

        this.floorLock = new ReentrantLock(true);
        this.floorLockCondition = floorLock.newCondition();
    }

    public static Floor of(int floorNumber, Building building) {
        return new Floor(floorNumber, building);
    }

    public Controller getController() {
        checkNotNull(building.getController());

        return building.getController();
    }


    public void callElevator(Direction direction) {
        checkNotNull(direction);
        checkNotNull(getController());

        getController().addCall(Call.of(floorNumber, direction));
    }

    public void addHuman(Human human) {
        checkNotNull(human);

        floorLock.lock();
        Direction direction = human.getCall().getDirection();
        if (direction == Direction.UP) {
            enqueue(queueUp, human);
        } else if (direction == Direction.DOWN) {
            enqueue(queueDown, human);
        }
        floorLock.unlock();

        log.info("human has been added to {}", human);
    }

    public int getNumberOfPeople(Direction direction) {
        checkNotNull(direction);

        floorLock.lock();
        direction = resolveDirection(direction);
        int count = direction.equals(Direction.UP) ? queueUp.size() : queueDown.size();
        floorLock.unlock();

        return count;
    }

    @Nullable
    public Human getFirstHuman(Direction direction) {
        checkNotNull(direction);

        floorLock.lock();
        direction = resolveDirection(direction);
        Human human = direction.equals(Direction.UP) ? queueUp.peek() : queueDown.peek();
        floorLock.unlock();

        return human;
    }

    @Nullable
    public Human pollFirstHuman(Direction direction) {
        checkNotNull(direction);

        Human human = null;

        floorLock.lock();
        direction = resolveDirection(direction);
        if (getFirstHuman(direction) != null) {
            human = direction.equals(Direction.UP) ? queueUp.poll() : queueDown.poll();

            if (getFirstHuman(direction) != null
                    && getController().canCallElevator(Objects.requireNonNull(getFirstHuman(direction)).getCall())) {
                callElevator(direction);
            }

            log.info("human has been polled {}", human);
        }
        floorLock.unlock();

        return human;
    }

    public List<Human> getHumanQueue(Direction direction) {
        checkNotNull(direction);

        floorLock.lock();
        direction = resolveDirection(direction);
        floorLock.unlock();

        return direction.equals(Direction.UP) ? ImmutableList.copyOf(queueUp) : ImmutableList.copyOf(queueDown);
    }

    private void enqueue(Queue<Human> queue, Human human) {
        if (queue.isEmpty()) {
            human.pushButton();
        }
        queue.add(human);
    }

    private Direction resolveDirection(Direction direction) {
        if (direction == Direction.NONE) {
            return queueUp.size() > queueDown.size() ? Direction.UP : Direction.DOWN;
        }

        return direction;
    }
}
