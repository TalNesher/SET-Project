package bguspl.set.ex;

import bguspl.set.Env;

import java.rmi.server.ObjID;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Scanner;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    protected Object lock = new Object();
    protected Semaphore semaphoreLock = new Semaphore(1, true);
    protected Queue<Player> setsToCheck = new LinkedList<>();
    long sleepTime;
    long timeOutMillis;
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    protected Thread[] playersThreads;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    protected volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        sleepTime = 1000;
        timeOutMillis = env.config.turnTimeoutMillis;
        playersThreads = new Thread[players.length];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        placeCardsOnTable();
        for (int i = 0; i < players.length; i++) {
            playersThreads[i] = new Thread(players[i], env.config.playerNames[i]);
            playersThreads[i].start();
        }
        while (!shouldFinish()) {
            timerLoop();
            updateTimerDisplay(true); // set back to 60 sec
            removeAllCardsFromTable(true);
        }
        System.out.println("the dealer decide the game should be finished");
        terminate();
        System.out.println(Thread.currentThread().getName() + " lets announce the winners");
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + timeOutMillis;
        while (!terminate && System.currentTimeMillis() < reshuffleTime && !shouldFinish()) {
            updateTimerDisplay(false); // set to a sec less
            synchronized (lock) {
                while (!setsToCheck.isEmpty() && !terminate) { // if the player declare on a set while the diler is not
                                                               // waiting
                    checkSet();
                }
            }
            sleepUntilWokenOrTimeout();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     * *******************Bounus**********************************
     */
    public void terminate() {
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
            playersThreads[i].interrupt(); // if the players are waiting for key press or for the dealer to check their
                                           // set
            try {
                playersThreads[i].join();
                System.out.println("player " + i + " thread has joined");
            } catch (InterruptedException e) {
            }
        }
        terminate = true;

    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     * synchronized to remove all the cards from the table without interaptions -
     * like placing a token in the middle
     */
    private void removeCardsFromTable(Integer[] slots) {
        if (!shouldFinish()) {// for ending the game without waiting the TurnTimeOutSeconds
            table.canChangeTable = false; // couse a major delay, why?
            for (int slot : slots) {
                table.removeCard(slot, players);
            }
            if (!terminate)
                placeCardsOnTable();

        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if (!shouldFinish()) { // for ending the game without waiting the TurnTimeOutSeconds
            while (!deck.isEmpty() && table.emptySlots.size() != 0) {
                Random rnd = new Random();
                int x = rnd.nextInt(table.emptySlots.size());
                int slot = table.emptySlots.get(x);
                x = rnd.nextInt(deck.size());
                int card = deck.get(x);
                deck.remove(x);
                table.placeCard(card, slot);
            }
        }
        if (env.config.hints)
            table.hints();
        table.canChangeTable = true;
        synchronized (table.canChangeLock) {
            table.canChangeLock.notify();
        }

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (lock) {// synchronized so the player could notify the dealer he has a set
            try {
                lock.wait(getSleepTime());
            } catch (InterruptedException ex) {
            }
            
            while (!setsToCheck.isEmpty()) { // if the player declare on a set when the diler is waiting
                checkSet();
            }
        }
    }

    private void checkSet() {
        Player p = setsToCheck.poll();
        synchronized (p) { // so the player will wait untill we finish checking the set, and the dealer
                           // will notify him
            if (p.myTokens.size() == env.config.featureSize) { // check if no token was deleted while checking other
                                                               // players sets
                Integer[] slots = vectorToArray(p.myTokens);
                int[] cards = slotsToCards(slots);
                if (env.util.testSet(cards)) {
                    removeCardsFromTable(slots);
                    updateTimerDisplay(true);
                    p.flag = 1;
                } else {
                    p.flag = 0;
                }
            } else {
                p.flag = 2;
            }
            p.notify();
        }

    }

    public Integer[] vectorToArray(Vector<Integer> toConvert) {
        Object[] objArray = toConvert.toArray();
        Integer[] slots = Arrays.copyOf(objArray, objArray.length, Integer[].class);
        return slots;
    }

    public void IHaveASet(Player p) {
        try {
            semaphoreLock.acquire();
            synchronized (lock) { // semaphore to maintaining the order of sets call
                setsToCheck.add(p);
                lock.notify();
            }
            semaphoreLock.release();
        } catch (Exception ignore) {
        }

    }

    private int[] slotsToCards(Integer[] slots) {
        table.stcRWLock.readLock().lock();
            int[] cards = new int[slots.length];
            for (int i = 0; i < slots.length; i++) {
                cards[i] = table.slotToCard(slots[i]);
            }
        table.stcRWLock.readLock().unlock();
        return cards;
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            setSleepTime(1000);
            env.ui.setCountdown(timeOutMillis, false);
            reshuffleTime = System.currentTimeMillis() + timeOutMillis;
        } else if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis) {
            setSleepTime(10);
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
        } else {
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable(boolean shouldIPlaceCard) {
        // after the AI will put 3 slots in the queue of each player, it will wait so
        // wont waste CPU time
        table.canChangeTable = false; // stoping the AI threads and the option to place tokens

        for (int i = 0; i < 12; i++) {
            if (table.slotToCard(i) != null) {
                deck.add(table.slotToCard(i));
                table.removeCard(i, players);
            }
        }
        for (Player p : players) {
            synchronized (p.myQueue) { // becouse we are changing the queue
                p.myQueue.clear(); // deleting the slots the AI entered to the queue
            }
        }
        if (shouldIPlaceCard) {
            placeCardsOnTable();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        List<Integer> winnersIDList = new LinkedList<>();
        int maxScore = 0;
        for (Player p : players) {
            if (p.score() >= maxScore) {
                if (p.score() > maxScore) {
                    winnersIDList.clear();
                    maxScore = p.score();
                }
                winnersIDList.add(p.id);
            }
        }
        int[] winnersPlayersID = new int[winnersIDList.size()];
        Iterator<Integer> iter = winnersIDList.iterator();
        int i = 0;
        while (iter.hasNext()) {
            winnersPlayersID[i] = iter.next();
            i++;
        }

        removeAllCardsFromTable(false);
        env.ui.announceWinner(winnersPlayersID);
        System.out.println("not dinidh yet?");
    }

    public long getSleepTime() {
        return this.sleepTime;
    }

    public void setSleepTime(long x) {
        this.sleepTime = x;
    }

}
