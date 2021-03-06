package com.lishid.openinv.util;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;

/**
 * A minimal time-based cache implementation backed by a HashMap and TreeMultimap.
 * 
 * @author Jikoo
 */
public class Cache<K, V> {

    private final Map<K, V> internal;
    private final Multimap<Long, K> expiry;
    private final long retention;
    private final Function<V> inUseCheck, postRemoval;

    /**
     * Constructs a Cache with the specified retention duration, in use function, and post-removal function.
     * 
     * @param retention duration after which keys are automatically invalidated if not in use
     * @param inUseCheck Function used to check if a key is considered in use
     * @param postRemoval Function used to perform any operations required when a key is invalidated
     */
    public Cache(long retention, Function<V> inUseCheck, Function<V> postRemoval) {
        this.internal = new HashMap<K, V>();

        this.expiry = TreeMultimap.create(new Comparator<Long>() {
                    @Override
                    public int compare(Long long1, Long long2) {
                        return long1.compareTo(long2);
                    }
                },
                new Comparator<K>() {
                    @Override
                    public int compare(K k1, K k2) {
                        return 0;
                    }
                });

        this.retention = retention;
        this.inUseCheck = inUseCheck;
        this.postRemoval = postRemoval;
    }

    /**
     * Set a key and value pair. Keys are unique. Using an existing key will cause the old value to
     * be overwritten and the expiration timer to be reset.
     * 
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     */
    public void put(K key, V value) {
        // Invalidate key - runs lazy check and ensures value won't be cleaned up early
        invalidate(key);

        internal.put(key, value);
        expiry.put(System.currentTimeMillis() + retention, key);
    }

    /**
     * Returns the value to which the specified key is mapped, or null if no value is mapped for the key.
     * 
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or null if no value is mapped for the key
     */
    public V get(K key) {
        // Run lazy check to clean cache
        lazyCheck();

        return internal.get(key);
    }

    /**
     * Returns true if the specified key is mapped to a value.
     * 
     * @param key key to check if a mapping exists for
     * @return true if a mapping exists for the specified key
     */
    public boolean containsKey(K key) {
        // Run lazy check to clean cache
        lazyCheck();

        return internal.containsKey(key);
    }

    /**
     * Forcibly invalidates a key, even if it is considered to be in use.
     * 
     * @param key key to invalidate
     */
    public void invalidate(K key) {
        // Run lazy check to clean cache
        lazyCheck();

        if (!internal.containsKey(key)) {
            // Value either not present or cleaned by lazy check. Either way, we're good
            return;
        }

        // Remove stored object
        internal.remove(key);

        // Remove expiration entry - prevents more work later, plus prevents issues with values invalidating early
        for (Iterator<Map.Entry<Long, K>> iterator = expiry.entries().iterator(); iterator.hasNext();) {
            if (key.equals(iterator.next().getValue())) {
                iterator.remove();
                break;
            }
        }
    }

    /**
     * Forcibly invalidates all keys, even if they are considered to be in use.
     */
    public void invalidateAll() {
        for (V value : internal.values()) {
            postRemoval.run(value);
        }
        expiry.clear();
        internal.clear();
    }

    /**
     * Invalidate all expired keys that are not considered in use. If a key is expired but is
     * considered in use by the provided Function, its expiration time is reset.
     */
    private void lazyCheck() {
        long now = System.currentTimeMillis();
        long nextExpiry = now + retention;
        for (Iterator<Map.Entry<Long, K>> iterator = expiry.entries().iterator(); iterator.hasNext();) {
            Map.Entry<Long, K> entry = iterator.next();

            if (entry.getKey() > now) {
                break;
            }

            iterator.remove();

            if (inUseCheck.run(internal.get(entry.getValue()))) {
                expiry.put(nextExpiry, entry.getValue());
                continue;
            }

            V value = internal.remove(entry.getValue());

            if (value == null) {
                continue;
            }

            postRemoval.run(value);
        }
    }

}
