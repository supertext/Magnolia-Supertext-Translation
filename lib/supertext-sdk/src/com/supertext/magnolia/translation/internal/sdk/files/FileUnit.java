package com.supertext.magnolia.translation.internal.sdk.files;

import java.util.Objects;

/** One unit of a file that was read: a segment's key and its source checksum at export time. */
public final class FileUnit {

    private final String key;
    private final String checksum;

    public FileUnit(String key, String checksum) {
        this.key = Objects.requireNonNull(key, "key");
        this.checksum = checksum;
    }

    public String getKey() {
        return key;
    }

    /** The source's checksum at export time, or {@code null} when the file doesn't carry one. */
    public String getChecksum() {
        return checksum;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FileUnit)) {
            return false;
        }
        FileUnit other = (FileUnit) o;
        return key.equals(other.key) && Objects.equals(checksum, other.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, checksum);
    }

    @Override
    public String toString() {
        return "FileUnit(" + key + ", " + checksum + ")";
    }
}
