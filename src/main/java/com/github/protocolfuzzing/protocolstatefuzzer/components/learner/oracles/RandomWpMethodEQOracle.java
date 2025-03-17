package com.github.protocolfuzzing.protocolstatefuzzer.components.learner.oracles;

import de.learnlib.oracle.EquivalenceOracle;
import de.learnlib.oracle.MembershipOracle.MealyMembershipOracle;
import de.learnlib.query.DefaultQuery;
import net.automatalib.automaton.transducer.MealyMachine;
import net.automatalib.word.Word;
import net.automatalib.word.WordBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Implements an equivalence test by applying the WP-method test on the given
 * hypothesis automaton as described in "Test Selection Based on Finite State Models" by {@literal S. Fujiwara et al}.
 * <p>
 * Adapted from an EQ oracle implementation in LearnLib's development branch not
 * available in the version we use, see
 * <a href="https://github.com/mtf90/learnlib/blob/develop/oracles/equivalence-oracles/src/main/java/de/learnlib/oracle/equivalence/RandomWpMethodEQOracle.java">RandomWpMethodEQOracle</a>.
 * Our adaptation is randomizing access sequence generation.
 * <p>
 * Instead of enumerating the test suite in order, this is a sampling implementation:
 * <ol>
 * <li> Sample uniformly from the states for a prefix
 * <li> Sample geometrically a random word
 * <li> Sample a word from the set of suffixes / state identifiers (either local or global).
 * </ol>
 * <p>
 * There are two parameters:
 * <ul>
 * <li> minimalSize determines the minimal size of the random word. This is useful when one first performs a
 *      W(p)-method with some depth and continues with this randomized tester from that depth onward
 * <li> rndLength determines the expected length of the random word. The expected length in effect is minimalSize + rndLength.
 *      In the unbounded case it will not terminate for a correct hypothesis.
 * </ul>
 *
 * @param <I>  input symbol type
 * @param <O>  output symbol type
 */
public class RandomWpMethodEQOracle<I,O> implements EquivalenceOracle.MealyEquivalenceOracle<I, O> {

    /** Stores the constructor parameter. */
    protected List<MealyMembershipOracle<I, O>>  sulOracles;

    /** Stores the constructor parameter. */
    protected int minimalSize;

    /** Stores the constructor parameter. */
    protected int rndLength;

    /** Stores the constructor parameter. */
    protected int bound;

    /** Stores the constructor parameter. */
    protected long seed;

    /**
     * Constructs a new instance from the given parameters, which represents an unbounded testing oracle.
     *
     * @param sulOracle    the oracle which answers tests
     * @param minimalSize  the minimal size of the random word
     * @param rndLength    the expected length (in addition to minimalSize) of random word
     * @param seed         the seed to be used for randomness
     */
    public RandomWpMethodEQOracle(MealyMembershipOracle<I, O> sulOracle,
        int minimalSize, int rndLength, long seed) {

        this.sulOracles = Collections.singletonList(sulOracle);
        this.minimalSize = minimalSize;
        this.rndLength = rndLength;
        this.seed = seed;
        this.bound = 0;
    }

    /**
     * Constructs a new instance from the given parameters, which represents a bounded testing oracle.
     *
     * @param sulOracles    the oracle which answers tests
     * @param minimalSize  the minimal size of the random word
     * @param rndLength    the expected length (in addition to minimalSize) of random word
     * @param bound        the bound (set to 0 for unbounded).
     * @param seed         the seed to be used for randomness
     */
    public RandomWpMethodEQOracle(List<MealyMembershipOracle<I, O>> sulOracles,
        int minimalSize, int rndLength, int bound, long seed) {

        this.sulOracles = sulOracles;
        this.minimalSize = minimalSize;
        this.rndLength = rndLength;
        this.bound = bound;
        this.seed = seed;
    }

    /**
     * Tries to find a counterexample using {@link #doFindCounterExample(MealyMachine, Collection)}.
     *
     * @param hypothesis  the hypothesis to be searched
     * @param inputs      the inputs to be used
     * @return            the counterexample or null
     */
    @Override
    public @Nullable DefaultQuery<I, Word<O>> findCounterExample(
        MealyMachine<?, I, ?, O> hypothesis, Collection<? extends I> inputs) {

        return doFindCounterExample(hypothesis, inputs);
    }

    /**
     * Implements the search technique.
     *
     * @param <S>         the type of a state
     * @param hypothesis  the hypothesis to be searched
     * @param inputs      the inputs to be used
     * @return            the counterexample or null
     */
    public <S> @Nullable DefaultQuery<I, Word<O>> doFindCounterExample(MealyMachine<S, I, ?, O> hypothesis,
        Collection<? extends I> inputs) {
        WpEQSequenceGenerator<I, Word<O>, S> generator = new WpEQSequenceGenerator<>(hypothesis, inputs);
        Random random = new Random(seed);

        List<S> states = new ArrayList<>(hypothesis.getStates());
        int remainingTasks = bound;

        // 创建线程池和CompletionService
        int numThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        System.out.println("[DEBUG] 开始并行执行测试 - 线程数: " + numThreads + ", 总任务数: " + bound);
        Instant startTime = Instant.now();
        int batchCounter = 0;
        int totalProcessed = 0;

        try {
            int batchSize = numThreads * 1; // 每批任务数量

            while (remainingTasks > 0) {
                batchCounter++;
                // 确定本批次要执行的任务数
                int currentBatchSize = Math.min(batchSize, remainingTasks);

                // 批次开始前的调试信息
                System.out.println(String.format(
                        "[DEBUG] 批次 #%d: 提交 %d 个任务 (已处理: %d, 剩余: %d)",
                        batchCounter, currentBatchSize, totalProcessed, remainingTasks
                ));
                Instant batchStartTime = Instant.now();
                List<DefaultQuery<I, Word<O>>> queries = new ArrayList<>(currentBatchSize);
                for (int i = 0; i < currentBatchSize; i++) {
                    WordBuilder<I> wb = new WordBuilder<>(minimalSize + rndLength + 1);
                    S randState = states.get(random.nextInt(states.size()));
                    wb.append(generator.getRandomAccessSequence(randState, random));
                    wb.append(generator.getRandomMiddleSequence(minimalSize, rndLength, random));
                    wb.append(generator.getRandomCharacterizingSequence(wb, random));

                    Word<I> queryWord = wb.toWord();
                    DefaultQuery<I, Word<O>> query = new DefaultQuery<>(queryWord);
                    queries.add(query);
                }
                // 提交一批任务
                List<Future<DefaultQuery<I, Word<O>>>> futures = new ArrayList<>(currentBatchSize);
                for (int i = 0; i < currentBatchSize; i++) {
                    final DefaultQuery<I, Word<O>> query = queries.get(i);
                    final int oracleIndex = i % sulOracles.size();
                    futures.add(executor.submit(() -> {
                        try {
                            sulOracles.get(oracleIndex).processQueries(Collections.singleton(query));
                            Word<O> hypOutput = hypothesis.computeOutput(query.getInput());

                            if (!Objects.equals(hypOutput, query.getOutput())) {
                                return query;  // 找到反例
                            }
                        } catch (Exception e) {
                            System.err.println("[ERROR] 处理查询时发生异常: " + e.getMessage());
                        }
                        return null;  // 没找到反例
                    }));
                }
                // 处理本批次的结果
                boolean foundCounterExample = false;
                DefaultQuery<I, Word<O>> counterExample = null;
                for (Future<DefaultQuery<I, Word<O>>> future : futures) {
                    try {
                        DefaultQuery<I, Word<O>> result = future.get();
                        if (result != null) {
                            foundCounterExample = true;
                            counterExample = result;
                            break;  // 找到反例，跳出循环
                        }
                    } catch (ExecutionException e) {
                        System.err.println("[ERROR] 任务执行异常: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                remainingTasks -= currentBatchSize;
                totalProcessed += currentBatchSize;

                // 批次结束后的调试信息
                Instant batchEndTime = Instant.now();
                Duration batchDuration = Duration.between(batchStartTime, batchEndTime);
                System.out.println(String.format(
                        "[DEBUG] 批次 #%d 完成: 耗时 %d 毫秒, %s",
                        batchCounter,
                        batchDuration.toMillis(),
                        foundCounterExample ? "找到反例!" : "未找到反例"
                ));

                if (foundCounterExample) {
                    // 如果找到反例，取消剩余任务并返回
                    System.out.println("[DEBUG] 已找到反例，终止剩余测试");
                    return counterExample;
                }
            }
        } catch (InterruptedException e) {
            System.err.println("[DEBUG] 测试被中断: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            // TODO maybe we can terminate immediately
            // 关闭线程池
            System.out.println("[DEBUG] 关闭线程池，测试完成");
//            executor.shutdownNow();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    List<Runnable> droppedTasks = executor.shutdownNow();
                    System.out.println("[DEBUG] 取消了 " + droppedTasks.size() + " 个剩余任务");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // 打印总体执行信息
            Instant endTime = Instant.now();
            Duration totalDuration = Duration.between(startTime, endTime);
            System.out.println(String.format(
                    "[DEBUG] 测试总结: 共处理 %d 个任务, 总耗时 %d 毫秒, 完成了 %.2f%%",
                    totalProcessed,
                    totalDuration.toMillis(),
                    (totalProcessed * 100.0) / bound
            ));
        }
        // no counter example found within the bound
        return null;
    }
}
