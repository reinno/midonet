/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nonnull;

import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyICMPEcho;

/**
 * An ovs datapath flow match object. Contains an ordered list of
 * FlowKey&lt;?&gt; instances.
 *
 * @see FlowKey
 * @see org.midonet.odp.flows.FlowKeys
 */
public class FlowMatch {

    private boolean userSpaceOnly = false;
    private List<FlowKey<?>> keys = new ArrayList<FlowKey<?>>();

    public FlowMatch() {
        keys = null;
    }

    /**
     * BEWARE: this method does a direct assign of keys to the private
     * collection.
     *
     * @param keys
     */
    public FlowMatch(@Nonnull List<FlowKey<?>> keys) {
        this.setKeys(keys);
    }

    public FlowMatch addKey(FlowKey<?> key) {
        if (keys == null) {
            keys = new ArrayList<FlowKey<?>>();
        }
        keys.add(key);
        userSpaceOnly |= (key instanceof FlowKey.UserSpaceOnly);
        return this;
    }

    @Nonnull
    public List<FlowKey<?>> getKeys() {
        return keys;
    }

    public FlowMatch setKeys(@Nonnull List<FlowKey<?>> keys) {
        this.keys = keys;
        this.userSpaceOnly = false;
        Iterator<FlowKey<?>> it = keys.iterator();
        while (!userSpaceOnly && it.hasNext()) {
            FlowKey<?> key = it.next();
            userSpaceOnly |= (key instanceof FlowKey.UserSpaceOnly);
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowMatch flowMatch = (FlowMatch) o;

        if (keys == null ? flowMatch.keys != null
                         : !keys.equals(flowMatch.keys))
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return keys != null ? keys.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "FlowMatch{ keys=" + keys +
               ", UserSpaceOnly=" + userSpaceOnly + "}";
    }

    /**
     * Tells if all the FlowKeys contained in this match are compatible with
     * Netlink.
     *
     * @return
     */
    public boolean isUserSpaceOnly() {
        return userSpaceOnly;
    }

}