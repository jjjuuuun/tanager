package dev.tanager.domain.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 실패할 수 있는 계산의 결과. 도메인 오류는 예외가 아니라 {@code E} 값으로 돌려준다(java-style 규칙 3 · 4).
 *
 * @param <T> 성공 값
 * @param <E> 오류 값(보통 sealed interface)
 */
public sealed interface Result<T, E> {

    /** 성공. */
    record Ok<T, E>(T get) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(get, "Ok 값");
        }
    }

    /** 실패. */
    record Err<T, E>(E get) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(get, "Err 오류");
        }
    }

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok<T, E>;
    }

    default Optional<T> value() {
        return switch (this) {
            case Ok<T, E> ok -> Optional.of(ok.get());
            case Err<T, E> err -> Optional.empty();
        };
    }

    default Optional<E> error() {
        return switch (this) {
            case Ok<T, E> ok -> Optional.empty();
            case Err<T, E> err -> Optional.of(err.get());
        };
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<T, E> ok -> Result.ok(f.apply(ok.get()));
            case Err<T, E> err -> Result.err(err.get());
        };
    }

    default <F> Result<T, F> mapError(Function<? super E, ? extends F> f) {
        return switch (this) {
            case Ok<T, E> ok -> Result.ok(ok.get());
            case Err<T, E> err -> Result.err(f.apply(err.get()));
        };
    }

    default <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> f) {
        return switch (this) {
            case Ok<T, E> ok -> f.apply(ok.get());
            case Err<T, E> err -> Result.err(err.get());
        };
    }

    default <R> R fold(Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
        return switch (this) {
            case Ok<T, E> ok -> onOk.apply(ok.get());
            case Err<T, E> err -> onErr.apply(err.get());
        };
    }
}
