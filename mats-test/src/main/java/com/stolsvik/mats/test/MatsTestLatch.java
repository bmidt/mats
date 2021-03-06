package com.stolsvik.mats.test;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-utility: Gives a latch-functionality facilitating communication back from typically a Mats Terminator to the
 * main-thread that sent a message to some processor, and is now waiting for the Terminator to get the result.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class MatsTestLatch {

    public interface Result<I, S> {
        I getData();

        S getState();

        Map<String, byte[]> getBinaries();

        Map<String, String> getStrings();
    }

    /**
     * Waits for 2,5 seconds.
     *
     * @return same as {@link #waitForResult(long)}.
     */
    public <I, S> Result<I, S> waitForResult() {
        return waitForResult(2500);
    }

    /**
     * Parks this thread, waiting for the specified time for {@link #resolve(Object, Object) resolve(..)} to be invoked
     * by some other thread, returning the result. If the result is already in, it immediately returns. If the result
     * does not come within timeout, an {@link AssertionError} is raised.
     *
     * @param timeout
     *            the max time to wait.
     * @return the {@link Result}. Throws {@link AssertionError} if not gotten within timeout.
     */
    public <I, S> Result<I, S> waitForResult(long timeout) {
        synchronized (this) {
            if (!_resolved) {
                try {
                    this.wait(timeout);
                }
                catch (InterruptedException e) {
                    throw new AssertionError("Should not get InterruptedException here.", e);
                }
            }

            if (!_resolved) {
                throw new AssertionError("After waiting for " + timeout + " ms, the result was not present.");
            }

            Result<I, S> result = new Result<I, S>() {
                @SuppressWarnings("unchecked")
                private I _idto = (I) _dto;
                @SuppressWarnings("unchecked")
                private S _isto = (S) _sto;
                private Map<String, byte[]> _ibinaries = _binaries;
                private Map<String, String> _istrings = _strings;

                @Override
                public I getData() {
                    return _idto;
                }

                @Override
                public S getState() {
                    return _isto;
                }

                @Override
                public Map<String, byte[]> getBinaries() {
                    return _ibinaries;
                }

                @Override
                public Map<String, String> getStrings() {
                    return _istrings;
                }
            };

            // Null out the latch, for reuse.
            _resolved = false;
            _sto = null;
            _dto = null;
            _binaries = new HashMap<>();
            _strings = new HashMap<>();
            return result;
        }
    }

    private boolean _resolved;
    private Object _dto;
    private Object _sto;
    private Map<String, byte[]> _binaries = new HashMap<>();
    private Map<String, String> _strings = new HashMap<>();

    /**
     * When this method is invoked, the waiting threads will be released. Thus, if you want to populate the key-value
     * maps with the result, you need to invoke those methods before.
     *
     * @param dto
     *            the incoming state object that the Mats processor initially received.
     * @param sto
     *            State object.
     */
    public void resolve(Object dto, Object sto) {
        synchronized (this) {
            if (_resolved) {
                throw new IllegalStateException("Already set, but not consumed: Cannot set again.");
            }
            _resolved = true;
            _dto = dto;
            _sto = sto;
            this.notifyAll();
        }
    }
}
