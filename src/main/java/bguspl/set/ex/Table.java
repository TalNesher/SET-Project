package bguspl.set.ex;
import bguspl.set.Env;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {
    protected volatile boolean canChangeTable = false; // doesnt need to be atomic or sinchronized becouse only the
                                                       // dealer can change this argument, but we want that every time
                                                       // it changes everyone will know imidiatly
    protected Object canChangeLock = new Object();
    protected Vector<Integer> emptySlots;
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    protected final ReadWriteLock stcRWLock;

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if
     *                   none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if
     *                   none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        emptySlots = new Vector<>();
        for (int i = 0; i < 12; i++) {
            emptySlots.add(i);
        }
        stcRWLock = new ReentrantReadWriteLock();
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the
     * table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted()
                    .collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(
                    sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     *         synchronized for slotToCard- that nobody change the state of the
     *         array while counting
     */
    public synchronized int countCards() {
        int cards = 0;
        stcRWLock.readLock().lock();
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        stcRWLock.readLock().unlock();
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * 
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     *       synchronized for the case that the cpu time was changed befor updating
     *       the display, and sombody else removed that card.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        cardToSlot[card] = slot;

        stcRWLock.writeLock().lock();
        slotToCard[slot] = card;
        stcRWLock.writeLock().unlock();

        emptySlots.remove(emptySlots.indexOf(slot)); // doesnt need to be synch because only the dealer is changing the
                                                     // list

        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * 
     * @param slot - the slot from which to remove the card.
     *             synchronized for the case that a player will put his Token on the
     *             card that we are removing, after i deleted the token from the
     *             tokens lists of all the player (129)
     */
    public synchronized void removeCard(int slot, Player[] players) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        for (Player p : players) {
            removeToken(p, slot);
        }
        cardToSlot[slotToCard[slot]] = null;

        stcRWLock.writeLock().lock();
        slotToCard[slot] = null;
        stcRWLock.writeLock().unlock();

        emptySlots.add(slot);
        env.ui.removeCard(slot);
    }

    /**
     * Places a player token on a grid slot.
     * 
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     *               
     */
    public void placeToken(Player player, int slot) {
        stcRWLock.readLock().lock();
        if (slotToCard[slot] != null && canChangeTable) {
            synchronized (player.myTokens) {
                player.myTokens.add(slot);
            }
            env.ui.placeToken(player.id, slot);
        }
        stcRWLock.readLock().unlock();
    }

    /**
     * Removes a token of a player from a grid slot.
     * 
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public boolean removeToken(Player player, int slot) {
        synchronized (player.myTokens) {
            if (player.myTokens.contains(slot)) {
                player.myTokens.remove(player.myTokens.indexOf(slot));
                env.ui.removeToken(player.id, slot);
                return true;
            }
            return false;
        }
    }

    public Integer slotToCard(int slot) {
        stcRWLock.readLock().lock();
        Integer x = slotToCard[slot];
        stcRWLock.readLock().unlock();
        return x;
    }
}
