package org.slf4j;

import java.io.Serializable;
import java.util.Iterator;

public interface Marker extends Serializable {

    String getName();

    void add(Marker var1);

    boolean remove(Marker var1);

    @Deprecated
    boolean hasChildren();

    boolean hasReferences();

    Iterator<Marker> iterator();

    boolean contains(Marker var1);

    boolean contains(String var1);

    boolean equals(Object var1);

    int hashCode();
}
