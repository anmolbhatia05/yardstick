package nl.tudelft.opencraft.yardstick.experiment;

import nl.tudelft.opencraft.yardstick.bot.Bot;
import nl.tudelft.opencraft.yardstick.bot.ai.task.Task;
import nl.tudelft.opencraft.yardstick.bot.ai.task.TaskStatus;
import nl.tudelft.opencraft.yardstick.bot.ai.task.WalkTask;
import nl.tudelft.opencraft.yardstick.bot.world.ConnectException;
import nl.tudelft.opencraft.yardstick.util.Vector3d;
import nl.tudelft.opencraft.yardstick.util.Vector3i;
import com.github.steveice10.mc.protocol.MinecraftProtocol;

import java.util.*;
import java.util.stream.Collectors;

public class Experiment4MultiWalkAround extends Experiment {

    private Random random;
    private final List<Bot> botList;
    private int botsTotal = 0;
    private long startMillis;
    private int durationInSeconds;
    private int secondsBetweenJoin;
    private int numberOfBotsPerJoin;
    private Map<Bot, Vector3d> botSpawnLocations = new HashMap<>();
    private long lastJoin = System.currentTimeMillis();

    public Experiment4MultiWalkAround() {
        super(3, "A simple test demonstrating A* movement");
        this.random = new Random();
        this.botList = Collections.synchronizedList(new ArrayList<>());
        this.startMillis = System.currentTimeMillis();
    }

    @Override
    protected void before() {
        this.botsTotal = Integer.parseInt(options.experimentParams.get("bots"));
        this.durationInSeconds = Integer.parseInt(options.experimentParams.getOrDefault("duration", "600"));
        this.secondsBetweenJoin = Integer.parseInt(options.experimentParams.getOrDefault("joininterval", "1"));
        this.numberOfBotsPerJoin = Integer.parseInt(options.experimentParams.getOrDefault("numbotsperjoin", "1"));
    }

    @Override
    protected void tick() {
        synchronized (botList) {
            List<Bot> disconnectedBots = botList.stream()
                    .filter(bot -> !bot.isConnected())
                    .collect(Collectors.toList());
            disconnectedBots.forEach(bot -> bot.disconnect("Bot is not connected"));
            botList.removeAll(disconnectedBots);
        }
        if (System.currentTimeMillis() - this.lastJoin > secondsBetweenJoin * 1000
                && botList.size() < this.botsTotal) {
            lastJoin = System.currentTimeMillis();
            int botsToConnect = Math.min(this.numberOfBotsPerJoin, this.botsTotal - botList.size());
            for (int i = 0; i < botsToConnect; i++) {
                new Thread(() -> {
                    Bot bot;
                    try {
                        bot = createBot();
                    } catch (ConnectException e) {
                        logger.warning(String.format("Could not connect bot %s on part %d.", options.host, options.port));
                        return;
                    }
                    botSpawnLocations.put(bot, bot.getPlayer().getLocation());
                    botList.add(bot);
                }).start();
            }
        }
        synchronized (botList) {
            for (Bot bot : botList) {
                botTick(bot);
            }
        }
    }

    /**
     * Function to make bot walk in a specific area.
     *
     * @param originalLocation Original location of bot
     * @return New random location in a field that has the original location at
     * its center.
     */
    private Vector3i getNewFieldLocation(Vector3d originalLocation) {
        int maxDist = 64;
        int minDist = 0;
        int distance = random.nextInt(maxDist - minDist) + minDist;
        int angle = random.nextInt(360);
        int newX = (int) (originalLocation.getX() + (distance * Math.cos(angle)));
        int newZ = (int) (originalLocation.getZ() + (distance * Math.sin(angle)));
        return new Vector3i(newX, 4, newZ);
    }

    private Vector3i getNewLongDistanceTarget(Vector3d originalLocation) {
        int maxDist = 64 * 5;
        int minDist = 64 * 1;
        int distance = random.nextInt(maxDist - minDist) + minDist;
        int angle = random.nextInt(360);
        int newX = (int) (originalLocation.getX() + (distance * Math.cos(angle)));
        int newZ = (int) (originalLocation.getZ() + (distance * Math.sin(angle)));
        return new Vector3i(newX, 4, newZ);
    }

    private void botTick(Bot bot) {
        Task t = bot.getTask();
        if (t == null || t.getStatus().getType() != TaskStatus.StatusType.IN_PROGRESS) {
            Vector3d spawnLocation = botSpawnLocations.get(bot);
            if (spawnLocation == null) {
                logger.warning(String.format("Bot %s has unknown spawn location.", bot.getName()));
                // TODO How did we get here?
                bot.disconnect("Could not find spawn location.");
                return;
            }
            Vector3i newLocation;
            if (random.nextDouble() < 0.1) {
                newLocation = getNewLongDistanceTarget(botSpawnLocations.get(bot));
            } else {
                newLocation = getNewFieldLocation(botSpawnLocations.get(bot));
            }
            logger.info(String.format("Setting task for bot to walk to %s", newLocation));
            bot.setTask(new WalkTask(bot, newLocation));
        }
    }

    private Bot createBot() throws ConnectException {
        Bot bot = new Bot(new MinecraftProtocol(UUID.randomUUID().toString().substring(0, 6)), options.host, options.port);
        if (this.getStats() != null) {
            bot.addSessionListener(this.getStats());
        }
        bot.connect();
        int sleep = 1000;
        int tries = 10;
        while (tries-- > 0 && (bot.getPlayer() == null || !bot.isConnected())) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
        if (!bot.isConnected()) {
            bot.disconnect("Make sure to close all connections.");
            throw new ConnectException();
        }
        return bot;
    }

    @Override
    protected boolean isDone() {
        boolean timeUp = System.currentTimeMillis() - this.startMillis > this.durationInSeconds * 1_000;
        if (timeUp) {
            return true;
        } else if (botList.size() > 0) {
            boolean allBotsDisconnected;
            synchronized (botList) {
                allBotsDisconnected = botList.stream().noneMatch(Bot::isConnected);
            }
            if (allBotsDisconnected) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void after() {
        for (Bot bot : botList) {
            bot.disconnect("disconnect");
        }
    }
}
