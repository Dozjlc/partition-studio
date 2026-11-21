package com.dsh.partitionstudio;

final class Partition {
    final String name;
    final String path;
    final long size;

    Partition(String name, String path, long size) {
        this.name = name;
        this.path = path;
        this.size = size;
    }

    String lowerName() {
        return name == null ? "" : name.toLowerCase(java.util.Locale.US);
    }
}
