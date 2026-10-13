package ac.grim.grimac.utils.latency;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

public class LatencyUtils {
    private record TransactionTask(int transactionId, Runnable task) {
    }

    private final ArrayDeque<TransactionTask> transactionMap = new ArrayDeque<>();
    private final GrimPlayer player;
    private final ArrayList<Runnable> tasksToRun = new ArrayList<>();

    public LatencyUtils(GrimPlayer player) {
        this.player = player;
    }

    public void addRealTimeTask(int transaction, Runnable runnable) {
        addRealTimeTask(transaction, false, runnable);
    }

    public void addRealTimeTaskAsync(int transaction, Runnable runnable) {
        addRealTimeTask(transaction, true, runnable);
    }

    public void addRealTimeTask(int transaction, boolean async, Runnable runnable) {
        if (player.lastTransactionReceived.get() >= transaction) {
            if (async) {
                player.runSafely(runnable);
            } else {
                runnable.run();
            }
            return;
        }

        synchronized (transactionMap) {
            transactionMap.addLast(new TransactionTask(transaction, runnable));
        }
    }

    public void handleNettySyncTransaction(int transaction) {
        synchronized (transactionMap) {
            tasksToRun.clear();

            Iterator<TransactionTask> iterator = transactionMap.iterator();
            while (iterator.hasNext()) {
                TransactionTask entry = iterator.next();
                int taskTransaction = entry.transactionId();

                // Transaction tasks are inserted in monotonic order. Once this
                // entry is too far ahead, every later task is also too far ahead.
                if (transaction + 1 < taskTransaction) {
                    break;
                }

                // Grim may run transaction tasks at most one tick early.
                if (transaction == taskTransaction - 1) {
                    continue;
                }

                tasksToRun.add(entry.task());
                iterator.remove();
            }

            for (Runnable runnable : tasksToRun) {
                try {
                    runnable.run();
                } catch (Exception e) {
                    handleRunnableError(e);
                }
            }
        }
    }

    private void handleRunnableError(Exception e) {
        LogUtil.error(
                "An error has occurred when running transactions for player: "
                        + player.user.getName(),
                e
        );
        if (CommonGrimArguments.KICK_ON_TRANSACTION_ERRORS.value()) {
            player.disconnect(
                    MessageUtil.miniMessage(
                            MessageUtil.replacePlaceholders(
                                    player,
                                    GrimAPI.INSTANCE.getConfigManager()
                                            .getDisconnectPacketError()
                            )
                    )
            );
        }
    }
}
