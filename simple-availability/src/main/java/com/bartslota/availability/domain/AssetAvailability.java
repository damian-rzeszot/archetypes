package com.bartslota.availability.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import com.bartslota.availability.common.Result;
import com.bartslota.availability.events.AssetActivated;
import com.bartslota.availability.events.AssetActivationRejected;
import com.bartslota.availability.events.AssetLockExpired;
import com.bartslota.availability.events.AssetLockRejected;
import com.bartslota.availability.events.AssetLocked;
import com.bartslota.availability.events.AssetUnlocked;
import com.bartslota.availability.events.AssetUnlockingRejected;
import com.bartslota.availability.events.AssetWithdrawalRejected;
import com.bartslota.availability.events.AssetWithdrawn;

import static com.bartslota.availability.common.Result.failure;
import static com.bartslota.availability.common.Result.success;

public class AssetAvailability {

    private static final String ASSET_LOCKED_REASON = "ASSET_CURRENTLY_LOCKED";
    private static final Duration INDEFINITE_LOCK_DURATION = Duration.ofDays(365);
    private static final String NO_LOCK_ON_THE_ASSET_REASON = "NO_LOCK_ON_THE_ASSET";
    private static final String NO_LOCK_DEFINED_FOR_OWNER_REASON = "NO_LOCK_DEFINED_FOR_OWNER";
    private static final String ASSET_ALREADY_ACTIVATED_REASON = "ASSET_ALREADY_ACTIVATED";

    private final AssetId assetId;
    private Lock currentLock = new MaintenanceLock();

    private AssetAvailability(AssetId assetId) {
        this.assetId = assetId;
    }

    public static AssetAvailability of(AssetId assetId) {
        return new AssetAvailability(assetId);
    }

    public Result<AssetActivationRejected, AssetActivated> activate() {
        if (currentLock instanceof MaintenanceLock) {
            currentLock = null;
            return success(AssetActivated.from(assetId));
        }
        return failure(AssetActivationRejected.from(assetId, ASSET_ALREADY_ACTIVATED_REASON));
    }

    public Result<AssetWithdrawalRejected, AssetWithdrawn> withdraw() {
        if (currentLock == null || currentLock instanceof MaintenanceLock) {
            currentLock = new WithdrawalLock();
            return success(AssetWithdrawn.from(assetId));
        }
        return failure(AssetWithdrawalRejected.from(assetId, ASSET_LOCKED_REASON));
    }

    public Result<AssetLockRejected, AssetLocked> lockFor(OwnerId ownerId, Duration time) {
        if (currentLock == null) { //we could handle lock reclaim for idempotency
            LocalDateTime validUntil = LocalDateTime.now().plus(time);
            currentLock = new OwnerLock(ownerId, validUntil);
            return success(AssetLocked.from(this.assetId, ownerId, validUntil));
        }
        return failure(AssetLockRejected.from(assetId, ownerId, ASSET_LOCKED_REASON));
    }

    public Result<AssetLockRejected, AssetLocked> lockIndefinitelyFor(OwnerId ownerId) {
        if (thereIsAnActiveLockFor(ownerId)) {
            LocalDateTime validUntil = LocalDateTime.now().plus(INDEFINITE_LOCK_DURATION);
            currentLock = new OwnerLock(ownerId, validUntil);
            return success(AssetLocked.from(this.assetId, ownerId, validUntil));
        } else {
            return failure(AssetLockRejected.from(assetId, ownerId, NO_LOCK_DEFINED_FOR_OWNER_REASON));
        }
    }

    public Result<AssetUnlockingRejected, AssetUnlocked> unlockFor(OwnerId ownerId, LocalDateTime at) {
        if (thereIsAnActiveLockFor(ownerId)) {
            currentLock = null;
            return success(AssetUnlocked.from(this.assetId, ownerId, at));
        }
        return failure(AssetUnlockingRejected.from(assetId, ownerId, NO_LOCK_ON_THE_ASSET_REASON));
    }

    public Optional<AssetLockExpired> unlockIfOverdue() {
        if (currentLock != null) {
            currentLock = null;
            return Optional.of(AssetLockExpired.from(assetId));
        }
        return Optional.empty();
    }

    public AssetId id() {
        return this.assetId;
    }

    public Optional<Lock> currentLock() {
        return Optional.ofNullable(currentLock);
    }

    private boolean thereIsAnActiveLockFor(OwnerId ownerId) {
        return Optional.ofNullable(currentLock).filter(lock -> lock.wasMadeFor(ownerId)).isPresent();
    }

    public AssetAvailability with(Lock lock) {
        this.currentLock = lock;
        return this;
    }

    public sealed interface Lock permits WithdrawalLock, MaintenanceLock, OwnerLock {

        OwnerId ownerId();

        default boolean wasMadeFor(OwnerId ownerId) {
            return ownerId().equals(ownerId);
        }
    }

    public static final class WithdrawalLock implements Lock {

        private static final OwnerId WITHDRAWAL_OWNER_ID = OwnerId.of("WITHDRAWAL");

        @Override
        public OwnerId ownerId() {
            return WITHDRAWAL_OWNER_ID;
        }

    }

    public static final class MaintenanceLock implements Lock {

        private static final OwnerId MAINTENANCE_OWNER_ID = OwnerId.of("MAINTENANCE");

        @Override
        public OwnerId ownerId() {
            return MAINTENANCE_OWNER_ID;
        }

    }

    public static final class OwnerLock implements Lock {

        private final OwnerId ownerId;
        private final LocalDateTime until;

        public OwnerLock(OwnerId ownerId, LocalDateTime until) {
            this.ownerId = ownerId;
            this.until = until;
        }

        @Override
        public OwnerId ownerId() {
            return ownerId;
        }

        public LocalDateTime getUntil() {
            return until;
        }
    }

}
