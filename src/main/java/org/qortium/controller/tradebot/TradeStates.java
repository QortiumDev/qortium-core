package org.qortium.controller.tradebot;

import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public class TradeStates {
    public enum State implements TradeBot.StateNameAndValueSupplier {
        MAKER_WAITING_FOR_AT_CONFIRM(10, false, false),
        MAKER_WAITING_FOR_TAKER_MESSAGE(15, true, true),
        MAKER_WAITING_FOR_LOCAL_LOCK(20, true, true),
        MAKER_WAITING_FOR_TAKER_HTLC(22, true, true),
        MAKER_WAITING_FOR_AT_REDEEM(25, true, true),
        MAKER_DONE(30, false, false),
        MAKER_REFUNDED(35, false, false),

        TAKER_WAITING_FOR_FOREIGN_LOCK(80, true, true),
        TAKER_WAITING_FOR_AT_LOCK(85, true, true),
        TAKER_WAITING_FOR_MAKER_REDEEM(90, true, true),
        TAKER_DONE(95, false, false),
        TAKER_REFUNDING_FOREIGN(105, true, true),
        TAKER_REFUNDED(110, false, false);

        private static final Map<Integer, State> map = stream(State.values()).collect(toMap(state -> state.value, state -> state));

        public final int value;
        public final boolean requiresAtData;
        public final boolean requiresTradeData;

        State(int value, boolean requiresAtData, boolean requiresTradeData) {
            this.value = value;
            this.requiresAtData = requiresAtData;
            this.requiresTradeData = requiresTradeData;
        }

        public static State valueOf(int value) {
            return map.get(value);
        }

        @Override
        public String getState() {
            return this.name();
        }

        @Override
        public int getStateValue() {
            return this.value;
        }
    }
}
