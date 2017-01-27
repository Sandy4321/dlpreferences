package it.poliba.enasca.ontocpnets.sat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A boolean formula in conjunctive normal form.
 */
public interface BooleanFormula {
    /**
     * Adds the specified clause.
     * @param clause
     */
    void addClause(IntStream clause);

    /**
     * Adds the negation of the specified clause, that is,
     * the conjunction of its negated literals (De Morgan's law).
     * @param clause
     */
    void addNegatedClause(IntStream clause);

    /**
     * Adds a trivial clause consisting of the specified literal.
     * @param literal
     */
    void addLiteral(int literal);

    /**
     * Returns the clauses that make up this formula.
     * @return
     */
    Stream<DIMACSLiterals> clauses();

    /**
     * Returns the number of clauses in this formula.
     * @return
     */
    int size();

    /**
     * Returns a copy of this formula.
     * @return
     */
    BooleanFormula copy();

    /**
     * Returns an empty formula.
     * @return
     */
    static BooleanFormula empty() {
        return new SimpleBooleanFormula();
    }

    /**
     * Returns a thread-safe implementation of <code>BooleanFormula</code>,
     * initialized to an empty formula.
     * @return
     */
    static BooleanFormula emptySynchronized() {
        return new SynchronizedBooleanFormula();
    }

    /**
     * Returns a <code>Collector</code> that accumulates input clauses into a new formula.
     * @return
     */
    static Collector<DIMACSLiterals, ?, ? extends BooleanFormula> toFormula() {
        return Collectors.collectingAndThen(
                Collectors.toCollection(HashSet::new),
                SimpleBooleanFormula::new);
    }

    /**
     * Returns a <code>Collector</code> that accumulates input clauses into a
     * thread-safe implementation of <code>BooleanFormula</code>.
     * @return
     */
    static Collector<DIMACSLiterals, ?, ? extends BooleanFormula> toSynchronizedFormula() {
        return Collector.of(
                () -> Collections.<DIMACSLiterals>synchronizedSet(new HashSet<>()),
                Set::add,
                (left, right) -> { left.addAll(right); return left; },
                SynchronizedBooleanFormula::new,
                Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED);
    }
}