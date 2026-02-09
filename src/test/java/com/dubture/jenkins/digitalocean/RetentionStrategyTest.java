package com.dubture.jenkins.digitalocean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetentionStrategyTest {

    @Mock
    private DigitalOceanComputer digitalOceanComputer;

    @Mock
    private Slave slave;

    @Test
    void testCheckCycleNormal() {
        RetentionStrategy strategy = new RetentionStrategy(10, false);
        assertEquals(60L, strategy.checkCycle());
    }

    @Test
    void testCheckCycleNegativeIdleTermination() {
        RetentionStrategy strategy = new RetentionStrategy(-1, false);
        assertEquals(6L, strategy.checkCycle());
    }

    @Test
    void testCheckCycleOneShot() {
        RetentionStrategy strategy = new RetentionStrategy(10, true);
        assertEquals(6L, strategy.checkCycle());
    }

    @Test
    void testCheckCycleOneShotWithNegative() {
        RetentionStrategy strategy = new RetentionStrategy(-1, true);
        assertEquals(6L, strategy.checkCycle());
    }

    @Test
    void testIsIdleForTooLongNormal() {
        RetentionStrategy strategy = new RetentionStrategy(10, false);

        when(digitalOceanComputer.getNode()).thenReturn(slave);
        when(slave.getIdleTerminationTime()).thenReturn(10);
        when(digitalOceanComputer.getIdleStartMilliseconds()).thenReturn(System.currentTimeMillis() - 11 * 60 * 1000); // 11 minutes ago

        assertTrue(strategy.isIdleForTooLong(digitalOceanComputer));
    }

    @Test
    void testIsIdleForTooLongNotIdle() {
        RetentionStrategy strategy = new RetentionStrategy(10, false);

        when(digitalOceanComputer.getNode()).thenReturn(slave);
        when(slave.getIdleTerminationTime()).thenReturn(10);
        when(digitalOceanComputer.getIdleStartMilliseconds()).thenReturn(System.currentTimeMillis() - 5 * 60 * 1000); // 5 minutes ago

        assertFalse(strategy.isIdleForTooLong(digitalOceanComputer));
    }

    @Test
    void testIsIdleForTooLongZero() {
        RetentionStrategy strategy = new RetentionStrategy(0, false);

        when(digitalOceanComputer.getNode()).thenReturn(slave);
        when(slave.getIdleTerminationTime()).thenReturn(0);

        assertFalse(strategy.isIdleForTooLong(digitalOceanComputer));
    }

    @Test
    void testIsIdleForTooLongOneShot() {
        RetentionStrategy strategy = new RetentionStrategy(10, true);

        when(digitalOceanComputer.getNode()).thenReturn(slave);
        when(slave.getIdleTerminationTime()).thenReturn(10);
        when(slave.isOneShot()).thenReturn(true);
        when(digitalOceanComputer.isIdle()).thenReturn(true);
        when(digitalOceanComputer.isTemporarilyOffline()).thenReturn(false);

        assertTrue(strategy.isIdleForTooLong(digitalOceanComputer));
        verify(digitalOceanComputer).setTemporarilyOffline(eq(true), any());
    }

    @Test
    void testIsIdleForTooLongOneShotNotIdle() {
        RetentionStrategy strategy = new RetentionStrategy(10, true);

        when(digitalOceanComputer.getNode()).thenReturn(slave);
        when(slave.getIdleTerminationTime()).thenReturn(10);
        when(slave.isOneShot()).thenReturn(true);
        when(digitalOceanComputer.isIdle()).thenReturn(false);

        assertFalse(strategy.isIdleForTooLong(digitalOceanComputer));
    }

    @Test
    void testIsIdleForTooLongNegative() {
        RetentionStrategy strategy = new RetentionStrategy(-1, false);

        when(digitalOceanComputer.getNode()).thenReturn(slave);
        when(slave.getIdleTerminationTime()).thenReturn(-1);
        when(slave.isOneShot()).thenReturn(false);
        when(digitalOceanComputer.isIdle()).thenReturn(true);

        assertTrue(strategy.isIdleForTooLong(digitalOceanComputer));
    }

    @Test
    void testIsIdleForTooLongNullNode() {
        RetentionStrategy strategy = new RetentionStrategy(10, false);

        when(digitalOceanComputer.getNode()).thenReturn(null);

        assertFalse(strategy.isIdleForTooLong(digitalOceanComputer));
    }
}