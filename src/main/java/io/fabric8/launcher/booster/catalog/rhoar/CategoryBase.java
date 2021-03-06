/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.fabric8.launcher.booster.catalog.rhoar;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * This class is the base class for the types that we use to
 * divide the boosters into the categories: Missions, Runtimes
 * and Versions.
 */
public class CategoryBase implements Comparable<CategoryBase> {

    public static final String KEY_SUGGESTED = "suggested";

    public CategoryBase(String id, String name, @Nullable String description, Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.metadata = Collections.unmodifiableMap(metadata);
    }

    private final String id;

    private final String name;

    @Nullable
    private final String description;

    private final Map<String, Object> metadata;

    /**
     * This method is needed so the Web UI can know what's the internal ID used
     */
    public String getKey() {
        return getId();
    }

    /**
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return get description
     */
    @Nullable
    public String getDescription() {
        return description;
    }

    /**
     * @return get metadata
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public boolean isSuggested() {
        return (boolean)getMetadata().getOrDefault(KEY_SUGGESTED, false);
    }

    @Override
    public int compareTo(CategoryBase o) {
        return getName().compareTo(o.getName());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + id.hashCode();
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CategoryBase other = (CategoryBase) obj;
        return id.equals(other.id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [id=" + id + ", name=" + name + ", description=" + description + ", metadata=" + metadata + "]";
    }
}