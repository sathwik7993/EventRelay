# EventRelay — Redis Distributed Locks

This document details the distributed locking patterns and configurations implemented in EventRelay to prevent race conditions during outbox polling and scheduled maintenance tasks.

---

## 1. Why Distributed Locks?

In a multi-node Fargate deployment, multiple instances of the Outbox Poller run concurrently. If multiple instances poll the `outbox` table simultaneously without synchronization, they will scan the same rows and publish duplicate messages to SQS.
- EventRelay uses **Redis-based Distributed Locks** to designate a single "leader" node for outbox polling.
- All other nodes act as hot-standbys, ready to take over if the leader crashes.

---

## 2. Lock Acquisition Logic (Jedis/Lettuce implementation)

Locks are acquired using Redis atomic operations:

```java
@Component
@RequiredArgsConstructor
public class DistributedLockManager {

    private final StringRedisTemplate redisTemplate;
    private static final String LOCK_KEY = "lock:outbox:poller";

    public boolean acquireLock(String nodeId, long expireTimeMs) {
        // SET lock:outbox:poller nodeId NX PX expireTimeMs
        Boolean success = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            Object nativeConnection = connection.getNativeConnection();
            if (nativeConnection instanceof LettuceConnection) {
                // Command runs atomicity via Redis SET command
                return redisTemplate.opsForValue().setIfAbsent(
                    LOCK_KEY, 
                    nodeId, 
                    Duration.ofMillis(expireTimeMs)
                );
            }
            return false;
        });
        return Boolean.TRUE.equals(success);
    }
}
```

---

## 3. Redlock and Watchdog Patterns

To prevent locks from expiring while a long-running polling cycle is active:

- **Watchdog Thread (Lock Renewal)**: The poller node runs a background scheduler that renews the lock TTL (resetting it to 30 seconds) every 10 seconds.
- **Graceful Release**: When the service shuts down, the node releases the lock using a Redis Lua script to verify that it still owns the lock, preventing it from releasing a lock acquired by another node:
  ```lua
  if redis.call("get", KEYS[1]) == ARGV[1] then
      return redis.call("del", KEYS[1])
  else
      return 0
  end
  ```

---

## 4. Failover Recovery

- **Node Crash**: If the active leader node crashes, the watchdog thread terminates. The Redis lock key will expire automatically after its 30-second TTL.
- **Leader Promotion**: The hot-standby nodes attempt to acquire the lock every 10 seconds. Within 30 seconds of a crash, one of the standbys will succeed and resume outbox polling, guaranteeing high availability.
