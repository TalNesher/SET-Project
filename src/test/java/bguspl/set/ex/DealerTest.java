package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class DealerTest {
    MockLogger logger = new MockLogger();
    Properties properties = new Properties();
    Config config = new Config(logger, properties);
    Env env = new Env(logger, config, new MockUserInterface(), new MockUtil());
    Integer[] slotToCard = new Integer[config.tableSize];
    Integer[] cardToSlot = new Integer[config.deckSize];
    Table table = new Table(env, slotToCard, cardToSlot);
    Player player = new Player(env, null, table, 0, true);
    Player[] players = {player};
    Dealer dealer = new Dealer(env, table, players);

    @Test
    void iHaveASet()  {
        boolean isPlayerInSTC = dealer.setsToCheck.contains(player);
        assertEquals(false, isPlayerInSTC); //varifing that player is not yet in setsToCheck
        dealer.IHaveASet(player);
        isPlayerInSTC = dealer.setsToCheck.contains(player);
        assertEquals(true, isPlayerInSTC); //varifing that player is now in setsToCheck
    }

    @Test
    void setSleepTime() {
        dealer.setSleepTime(10);
        assertEquals(10, dealer.sleepTime); //varifing that the dealer is terminated
    }


    static class MockUserInterface implements UserInterface {
        @Override
        public void placeCard(int card, int slot) {}
        @Override
        public void removeCard(int slot) {}
        @Override
        public void setCountdown(long millies, boolean warn) {}
        @Override
        public void setElapsed(long millies) {}
        @Override
        public void setScore(int player, int score) {}
        @Override
        public void setFreeze(int player, long millies) {}
        @Override
        public void placeToken(int player, int slot) {}
        @Override
        public void removeTokens() {}
        @Override
        public void removeTokens(int slot) {}
        @Override
        public void removeToken(int player, int slot) {}
        @Override
        public void announceWinner(int[] players) {}
        @Override
        public void dispose(){};
    };

    static class MockUtil implements Util {
        @Override
        public int[] cardToFeatures(int card) {
            return new int[0];
        }

        @Override
        public int[][] cardsToFeatures(int[] cards) {
            return new int[0][];
        }

        @Override
        public boolean testSet(int[] cards) {
            return false;
        }

        @Override
        public List<int[]> findSets(List<Integer> deck, int count) {
            return null;
        }
        @Override
        public void spin(){};
    }

    static class MockLogger extends Logger {
        protected MockLogger() {
            super("", null);
        }
    }
}
