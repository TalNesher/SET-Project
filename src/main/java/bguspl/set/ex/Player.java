package bguspl.set.ex;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {
    protected Integer flag = -1; // changing when the dealer checked my set

    protected BlockingQueue<Integer> myQueue;

    protected Dealer dealer;

    protected Vector<Integer> myTokens;

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    protected volatile boolean terminate;

    /**
     * The current score of the player.
     */
    protected int score;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.myQueue = new ArrayBlockingQueue<>(env.config.featureSize);
        myTokens = new Vector<>(env.config.featureSize);

    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) {
            createArtificialIntelligence();
        }
        while (!terminate) {
            int slot;
            synchronized (myQueue) {
                while (myQueue.isEmpty()) { // removing the head of the queue in thread safe, cancle the buisy waiting
                    try {
                        myQueue.notify(); // prevent dead lock
                        myQueue.wait();
                    } catch (InterruptedException ex) {
                        System.out.println(Thread.currentThread().getName()
                                + " i need to be terminated byyyyyyyeeeeee from waiting for myQueue not to be ampty");
                        break;
                    }
                }
                if (terminate && myQueue.size() == 0) // if the game was ended and i have nothing to handle in myQueue
                    break;
                slot = myQueue.poll();
                if (!human) {
                    myQueue.notify(); // wake up the AI thread and tell him there is spce in the queue now
                }
            }
            boolean wasRemoved = table.removeToken(this, slot); // check if the token nedded to be removed and removes
                                                                // it
            if (!wasRemoved && myTokens.size() < env.config.featureSize) { // place token, only if we have room
                table.placeToken(this, slot);
                if (myTokens.size() == env.config.featureSize && !terminate) { // a "set" was created
                    synchronized (this) { // we synch here because we dont want the dealer to notify us before we are
                                          // sleeping
                        dealer.IHaveASet(this);
                        while (flag == -1) // the dealer has not finishing checking the set
                            try {
                                wait();
                            } catch (InterruptedException x) {
                                System.out.println(Thread.currentThread().getName()
                                        + " i need to be terminated byyyyyyyeeeeee from witing for the dealer response to check my set");
                                break;
                            }
                        if (flag == 0) { // the set was right
                            penalty();
                        } else if (flag == 1) { // the set was wrong
                            point();
                        }
                        flag = -1;
                    }
                }

            }
        }
        if (!human) {
            try {
                aiThread.interrupt();
                aiThread.join();
                System.out.println("the AI thread of player " + id + " has joined");
            } catch (Exception ex) {
                System.out.println("the AI thread of player " + id + "has not joined succsefuly");
            }
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rnd = new Random();
            while (!terminate) {
                int slot = rnd.nextInt(12);
                if (!table.canChangeTable) {
                    synchronized (table.canChangeLock) {
                        try { // dealing in the buisy wait while removingAllcardsFromTable
                            table.canChangeLock.wait();
                            table.canChangeLock.notify();
                        } catch (Exception ex) {
                            System.out.println(Thread.currentThread().getName()
                                    + " need to be terminated byyyyyyyeeeeee from can change lock");
                        }

                    }
                }
                keyPressed(slot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        this.terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        synchronized (myQueue) { // to cancle the buisy wait
            if (table.canChangeTable && table.slotToCard(slot) != null) { // only if the table can get its keypress and
                                                                          // not in the middle of replacing card, and
                                                                          // the slot has a card on
                if (!human) {
                    while (myQueue.size() == env.config.featureSize) {
                        try {
                            myQueue.wait();
                        } catch (InterruptedException ex1) {
                            System.out.println(Thread.currentThread().getName()
                                    + " i need to be terminated byyyyyyyeeeeee from keyPressed-witing for the queue to have room");
                            break;
                        }
                    }
                }
                if (!terminate)
                    myQueue.add(slot);
                myQueue.notify();
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     *       synchronized becouse it needs to wait
     */
    public synchronized void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        score++;
        env.ui.setScore(id, score);
        long time = env.config.pointFreezeMillis;
        freez(time);
        synchronized (myQueue) {
            myQueue.clear();
            myQueue.notify();
        }
    }

    /**
     * Penalize a player and perform other related actions.
     ** synchronized becouse it needs to wait
     */
    public synchronized void penalty() {
        long time = env.config.penaltyFreezeMillis;
        System.out.println(time);
        freez(time);
        synchronized (myQueue) {
            myQueue.clear();
            myQueue.notify();
        }
    }

    private void freez(long time) {
        env.ui.setFreeze(id,time);
        if(time%1000!=0)
        {
            try {
                Thread.currentThread().sleep(time%1000);
            } catch (InterruptedException ex) {
            }
            time=time-(time%1000);
        }
        while (time >0) {
            env.ui.setFreeze(id,time-1);
            try {
                Thread.currentThread().sleep(1000);
            } catch (InterruptedException ex) {
                break;
            }
            time = time - 1000;
        }
        env.ui.setFreeze(id, 0);

    }

    // synchronized becouse "score" is not final
    public synchronized int score() {
        return score;
    }

    public Integer[] vectorToArray(Vector<Integer> toConvert) {
        Object[] objArray = toConvert.toArray();
        Integer[] slots = Arrays.copyOf(objArray, objArray.length, Integer[].class);
        return slots;
    }
}
