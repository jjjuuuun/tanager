package dev.tanager.application.port.out;

/** 불러온 저장소에 다시 쓸 수 있는지. 읽기 전용이면 변경은 메모리에만 남는다. */
public enum StoreAccess {
    WRITABLE,
    READ_ONLY
}
