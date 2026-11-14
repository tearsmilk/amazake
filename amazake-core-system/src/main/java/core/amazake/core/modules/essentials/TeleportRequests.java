package core.amazake.core.modules.essentials;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище запросов телепорта (TPA) в памяти. Ключ — UUID того, КОМУ пришёл запрос: на игрока в любой момент висит
 * не больше одного входящего запроса (новый затирает старый). Потокобезопасно ({@link ConcurrentHashMap}),
 * записи — неизменяемые {@link Request}. Логика истечения чистая и покрыта юнит-тестами.
 */
final class TeleportRequests {

    /** Тип запроса: кто к кому перемещается при принятии. */
    enum Type {
        /** {@code /tpa}: отправитель телепортируется к получателю. */
        TPA,
        /** {@code /tpahere}: получатель телепортируется к отправителю. */
        TPAHERE
    }

    /** Неизменяемый запрос телепорта. */
    record Request(UUID requester, String requesterName, UUID target, String targetName, Type type,
                   long expiresAtMillis) {
        boolean isExpired(final long nowMillis) {
            return nowMillis >= this.expiresAtMillis;
        }
    }

    private final ConcurrentHashMap<UUID, Request> byTarget = new ConcurrentHashMap<>();

    /** Кладёт запрос, затирая предыдущий входящий этому же получателю. */
    void put(final Request request) {
        this.byTarget.put(request.target(), request);
    }

    /** Входящий запрос этому получателю, или null. */
    Request get(final UUID target) {
        return this.byTarget.get(target);
    }

    /** Снимает входящий запрос получателя и возвращает его (или null). */
    Request remove(final UUID target) {
        return this.byTarget.remove(target);
    }

    /** Снимает исходящий запрос этого отправителя (для {@code /tpcancel}) и возвращает его (или null). */
    Request removeByRequester(final UUID requester) {
        for (final Request r : this.byTarget.values()) {
            if (r.requester().equals(requester)) {
                if (this.byTarget.remove(r.target(), r)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Удаляет истёкшие запросы и возвращает их (чтобы уведомить отправителей). */
    List<Request> sweepExpired(final long nowMillis) {
        final List<Request> expired = new ArrayList<>();
        this.byTarget.forEach((target, request) -> {
            if (request.isExpired(nowMillis)) {
                expired.add(request);
            }
        });
        for (final Request r : expired) {
            this.byTarget.remove(r.target(), r);
        }
        return expired;
    }

    void clear() {
        this.byTarget.clear();
    }

    int size() {
        return this.byTarget.size();
    }
}
