package it.poliba.sisinflab.dlpreferences;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Sets;
import exception.PreferenceReasonerException;
import it.poliba.sisinflab.dlpreferences.sat.BooleanFormula;
import it.poliba.sisinflab.dlpreferences.sat.DimacsLiterals;
import it.poliba.sisinflab.dlpreferences.sat.SAT4JSolver;
import it.poliba.sisinflab.dlpreferences.tree.IntPreferenceForest;
import model.Outcome;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.ChangeApplied;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;
import org.semanticweb.owlapi.rdf.rdfxml.parser.IRIProvider;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * An ontological CP-net.
 */
public class OntologicalCPNet extends CPNet {

    /**
     * The constrained ontology, constructed by adding preference domain entities to the base ontology.
     */
    OWLOntology ontology;

    /**
     * Stores equivalent representations of the preference domain entities that were added to the base ontology.
     */
    private Table domainTable;

    /**
     * The SAT solver used internally to find satisfiable models for collections of ontological constraints,
     * such as {@link PreferenceGraph#getOptimumSet()} and {@link #getClosure()}.
     */
    private SAT4JSolver solver;

    /**
     * The factory object that creates {@link OWLReasoner} instances
     * when reasoning services are required.
     */
    private OWLReasonerFactory reasonerFactory;

    /**
     * The ontological closure, wrapped in a lazy initializer.
     */
    private Lazy<ConstraintSet<FeasibilityConstraint>> closure;

    /**
     * @param builder
     * @throws OWLOntologyCreationException if the base ontology cannot be copied into
     * a local {@link OWLOntologyManager} to create the constrained ontology.
     */
    private OntologicalCPNet(Builder builder) throws OWLOntologyCreationException {
        super(builder.baseCPNet);
        domainTable = new Table(builder);
        solver = new SAT4JSolver(domainTable.size());
        reasonerFactory = builder.reasonerFactory;
        closure = new Lazy<>(this::computeClosure);
        // Build a mapping between domain values and their OWL representations.
        OWLDataFactory dataFactory = builder.baseOntology.getOWLOntologyManager().getOWLDataFactory();
        Map<String, OWLClass> owlDomainValues = graph.domainValues()
                .collect(Collectors.toMap(
                        Function.identity(),
                        domainValue -> dataFactory.getOWLClass(domainTable.getIRI(domainValue))));
        // Build the new class definition axioms.
        Stream<OWLEquivalentClassesAxiom> classDefinitions = graph.domainValues()
                .map(domainValue -> dataFactory.getOWLEquivalentClassesAxiom(
                        owlDomainValues.get(domainValue),
                        builder.definitions.get(domainValue)));
        // Build the new partition axioms.
        Stream<OWLDisjointUnionAxiom> partitions = graph.domainMap()
                .values().stream()
                .map(domain -> dataFactory.getOWLDisjointUnionAxiom(
                        dataFactory.getOWLThing(),
                        domain.stream().map(owlDomainValues::get)));
        // Copy the base ontology into a local manager.
        ontology = OWLManager.createOWLOntologyManager()
                .copyOntology(builder.baseOntology, OntologyCopy.SHALLOW);
        // Add the new axioms to the constrained ontology.
        if (ontology.addAxioms(Stream.concat(classDefinitions, partitions))
                != ChangeApplied.SUCCESSFULLY) {
            throw new OWLRuntimeException("error while applying changes to the new ontology");
        }
        // Check the constrained ontology for consistency.
        OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);
        boolean arePrefsConsistent = reasoner.isConsistent();
        reasoner.dispose();
        if (!arePrefsConsistent) {
            throw new IllegalStateException("inconsistent set of preferences");
        }
    }

    public Table getDomainTable() {
        return domainTable;
    }

    /**
     * Retrieves the set of constraints that must be satisfied by undominated outcomes.
     * @return
     */
    public ConstraintSet<OptimalityConstraint> getOptimumSet() {
        return toConstraintSet(graph.getOptimumSet());
    }

    /**
     * Retrieves the ontological closure, that is the set of constraints
     * that must be satisfied by feasible outcomes.
     * @return
     */
    public ConstraintSet<FeasibilityConstraint> getClosure() {
        return closure.getOrCompute();
    }

    /**
     * Returns a <code>ConstraintSet</code> instance backed by the specified <code>Set</code>.
     * @param constraints
     * @return
     * @see #getOptimumSet()
     * @see #getClosure()
     */
    public <T extends Constraint> ConstraintSet<T> toConstraintSet(Set<T> constraints) {
        return new ConstraintSet<>(constraints, domainTable,
                ontology.getOWLOntologyManager().getOWLDataFactory());
    }

    /**
     * Computes the Pareto optimal outcomes using the ontological variant of the HARD-PARETO algorithm.
     * @return
     * @throws IOException if an internal call to {@link #dominates(Outcome, Outcome)}
     * results in an <code>IOException</code>
     */
    public Set<Outcome> paretoOptimal() throws IOException {
        ConstraintSet<OptimalityConstraint> optimalityConstraints =
                getOptimumSet();
        ConstraintSet<FeasibilityConstraint> feasibilityConstraints =
                getClosure();
        ConstraintSet<? extends Constraint> paretoOptimalityConstraints =
                toConstraintSet(Sets.union(optimalityConstraints, feasibilityConstraints));
        Set<DimacsLiterals> undominatedModels, feasibleModels, paretoOptimalModels;
        // Solve the constraints as boolean problems.
        undominatedModels = solveConstraints(optimalityConstraints)
                .collect(Collectors.toSet());
        feasibleModels = solveConstraints(feasibilityConstraints)
                .collect(Collectors.toSet());
        paretoOptimalModels = solveConstraints(paretoOptimalityConstraints)
                .collect(Collectors.toSet());
        Set<Outcome> feasibleOutcomes = feasibleModels.stream()
                .map(this::interpretModel)
                .collect(Collectors.toSet());
        Set<Outcome> paretoOptimalOutcomes = paretoOptimalModels.stream()
                .map(this::interpretModel)
                .collect(Collectors.toCollection(HashSet::new));
        // Check trivial conditions.
        if (paretoOptimalModels.equals(feasibleModels) ||
                (!undominatedModels.isEmpty() && paretoOptimalModels.equals(undominatedModels))) {
            return ImmutableSet.copyOf(paretoOptimalOutcomes);
        }
        // Compute the set of unverified outcomes, that is the set of feasible, non-optimal outcomes.
        Sets.SetView<Outcome> unverifiedOutcomes =
                Sets.difference(feasibleOutcomes, paretoOptimalOutcomes);
        // Search among unverified outcomes for additional Pareto optimal outcomes.
        for (Outcome unverified : unverifiedOutcomes) {
            boolean isDominated = false;
            Iterator<Outcome> feasibleIter = feasibleOutcomes.iterator();
            // Check whether the current unverified outcome is dominated by some feasible outcome.
            while (feasibleIter.hasNext() && !isDominated) {
                isDominated = dominates(feasibleIter.next(), unverified);
            }
            // If the current unverified outcome is undominated among feasible outcomes, it is optimal.
            if (!isDominated) {
                paretoOptimalOutcomes.add(unverified);
            }
        }
        return ImmutableSet.copyOf(paretoOptimalOutcomes);
    }

    /**
     * Creates a new buffering <code>OWLReasoner</code> using the internal {@link OWLReasonerFactory},
     * with the constrained ontology as the root ontology, then executes the specified reasoning service.
     * The return value of <code>service</code> is relayed to the caller.
     * After <code>service</code> returns, the reasoner is immediately disposed of.
     * @param service
     * @param <T> the type of the value returned by <code>service</code>
     * @return the value returned by <code>service</code>
     */
    public <T> T applyService(Function<OWLReasoner, T> service) {
        OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);
        T result = service.apply(reasoner);
        reasoner.dispose();
        return result;
    }

    /**
     * Translates a set of constraints into a boolean satisfiability problem
     * and finds satisfiable models.
     * @param constraints
     * @return
     */
    public Stream<DimacsLiterals> solveConstraints(
            ConstraintSet<? extends Constraint> constraints) {
        BooleanFormula formula = constraints.clauses().collect(BooleanFormula.toFormula());
        return solver.solveSAT(formula);
    }

    /**
     * Converts a satisfiable DIMACS model into an {@link Outcome}.
     * @param model
     * @return
     */
    public Outcome interpretModel(DimacsLiterals model) {
        Set<String> domainValuesInModel = model.stream()
                .filter(dimacsLiteral -> dimacsLiteral > 0)
                .mapToObj(domainTable::fromPositiveLiteral)
                .collect(Collectors.toSet());
        // Verify that the model contains exactly one domain value per preference variable.
        if (graph.size() != domainValuesInModel.size()) {
            throw new IllegalStateException(
                    String.format("incorrect model size: expected %d, got %d",
                            graph.size(),
                            domainValuesInModel.size()));
        }
        // Map each preference variable to the corresponding domain value in the model.
        Map<String, String> assignments = graph.domainMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .filter(domainValuesInModel::contains)
                                .findAny().get()
                ));
        try {
            return new Outcome(assignments);
        } catch (PreferenceReasonerException e) {
            throw new IllegalStateException("invalid outcome", e);
        }
    }

    private ConstraintSet<FeasibilityConstraint> computeClosure() {
        IntPreferenceForest forest = new IntPreferenceForest(domainTable.size());
        ClosureBuilder closureBuilder = new ClosureBuilder();
        while (!forest.isEmpty()) {
            forest.expand(closureBuilder::accept);
        }
        return closureBuilder.build();
    }

    /**
     * Returns a builder that builds an <code>OntologicalCPNet</code>
     * upon the specified <code>CPNet</code> and base ontology.
     * Any changes to <code>baseOntology</code> applied before invoking {@link Builder#build()}
     * will take effect.
     *
     * <p>The specified base ontology must be consistent and <em>named</em>
     * (that is, <code>baseOntology.getOntologyID().isAnonymous()</code> must return <code>false</code>);
     * if either condition is not satisfied, {@link Builder#build()}
     * will throw an {@link IllegalStateException}.
     * @param baseCPNet the parent object of the <code>OntologicalCPNet</code> instance to build
     * @param baseOntology
     * @return
     */
    public static Builder builder(CPNet baseCPNet, OWLOntology baseOntology) {
        Objects.requireNonNull(baseCPNet);
        Objects.requireNonNull(baseOntology);
        return new Builder(baseCPNet, baseOntology);
    }

    /**
     * Builds the ontological closure by accepting {@link FeasibilityConstraint}s
     * and checking whether they are eligible for inclusion in the closure.
     * A feasibility constraint is eligible if the axiom obtained from
     * {@link Constraint#asAxiom(OWLDataFactory, IRIProvider)} is entailed by the ontology.
     *
     * <p>This is a thread-safe implementation.
     */
    private class ClosureBuilder {
        private Set<FeasibilityConstraint> closure;
        private BooleanFormula closureAsFormula;
        private OWLDataFactory concurrentDataFactory;

        public ClosureBuilder() {
            closure = Collections.synchronizedSet(new HashSet<>());
            closureAsFormula = BooleanFormula.emptySynchronized();
            concurrentDataFactory = OWLManager.createConcurrentOWLOntologyManager().getOWLDataFactory();
        }

        /**
         * Returns <code>true</code> if the specified branch clause, interpreted as
         * a {@link FeasibilityConstraint}, is eligible for inclusion in the ontological closure.
         * A feasibility constraint is eligible if the axiom obtained from
         * {@link Constraint#asAxiom(OWLDataFactory, IRIProvider)} is entailed by the ontology.
         *
         * <p>If an eligible constraint is not redundant (that is, it is not already
         * entailed by the axioms collected in the closure so far), it is added to the closure.
         *
         * @param branch
         * @return
         */
        public boolean accept(IntStream branch) {
            DimacsLiterals branchClause = new DimacsLiterals(branch);
            // Check whether the current branch clause is entailed by the closure.
            if (solver.implies(closureAsFormula, branchClause)) {
                return false;
            }
            // Check whether the constrained ontology entails the current branch axiom.
            FeasibilityConstraint constraint = new FeasibilityConstraint(branchClause, domainTable);
            OWLSubClassOfAxiom branchAxiom = constraint.asAxiom(concurrentDataFactory, domainTable);
            if (applyService(reasoner -> reasoner.isEntailed(branchAxiom))) {
                closure.add(constraint);
                closureAsFormula.addClause(branchClause);
                return false;
            }
            return true;
        }

        /**
         * Returns an immutable <code>Set</code> containing the constraints collected so far.
         * @return
         */
        public ConstraintSet<FeasibilityConstraint> build() {
            return toConstraintSet(closure);
        }

    }

    /**
     * An equivalency table that stores different representations of user preferences.
     */
    public class Table implements ModelConverter {

        /**
         * Contains equivalent representations of user preferences.
         * Specifically:
         * <ul>
         *     <li>rows contain the string identifiers found in the preference specification file;</li>
         *     <li>columns contain positive integers, for use as DIMACS literals in boolean satisfiability problems;</li>
         *     <li>values contain the <code>IRI</code>s of the OWL classes that were added to the base ontology.</li>
         * </ul>
         *
         * <p>For example, consider the preference variables <i>A</i> and <i>B</i>, with domain values
         * <i>a1, a2, a3</i> and <i>b1, b2</i>, respectively. The corresponding table has the form:
         * <pre>{@code
         * "a1" <-> 1 -> "http://www.semanticweb.org/myname/myontology#a1"
         * "a2" <-> 2 -> "http://www.semanticweb.org/myname/myontology#a2"
         * "a3" <-> 3 -> "http://www.semanticweb.org/myname/myontology#a3"
         * "b1" <-> 4 -> "http://www.semanticweb.org/myname/myontology#b1"
         * "b2" <-> 5 -> "http://www.semanticweb.org/myname/myontology#b2"
         * }</pre>
         */
        private ImmutableTable<String, Integer, IRI> internalTable;

        /**
         * Constructs a <code>Table</code> by generating a unique {@link IRI}
         * for each element of {@link Builder#domainValues}.
         * A disambiguation policy is applied when a generated <code>IRI</code> clashes
         * with an existing <code>IRI</code> in the base ontology.
         * @param builder
         */
        private Table(Builder builder) {
            OWLOntologyID baseOntologyID = builder.baseOntology.getOntologyID();
            String baseIRIString = baseOntologyID.getOntologyIRI()
                    .orElseThrow(() -> new IllegalStateException("base ontology cannot be anonymous"))
                    .getIRIString();
            Set<IRI> existingIRIs = builder.baseOntology.classesInSignature()
                    .map(HasIRI::getIRI)
                    .collect(Collectors.toSet());
            IRIProvider converter = str -> Arrays.stream(Builder.DISAMBIGUATION_SUFFIXES)
                    .map(suffix -> baseIRIString + "#" + str + suffix)
                    .map(IRI::create)
                    .filter(iri -> !existingIRIs.contains(iri))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            String.format("Unable to generate a unique IRI for domain value '%s'", str)));
            List<String> domainList = graph.domainValues().collect(Collectors.toList());
            ImmutableTable.Builder<String, Integer, IRI> tableBuilder = ImmutableTable.builder();
            IntStream.range(0, domainList.size()).forEachOrdered(index -> {
                String domainValue = domainList.get(index);
                tableBuilder.put(domainValue, index+1, converter.getIRI(domainValue));
            });
            internalTable = tableBuilder.build();
        }

        /**
         * Retrieves the DIMACS representation of the specified domain element.
         * @param domainElement
         * @return
         * @throws NoSuchElementException if the argument does not exist in the internal table
         */
        @Override
        public int getPositiveLiteral(String domainElement) {
            Objects.requireNonNull(domainElement);
            return internalTable.row(domainElement).keySet().iterator().next();
        }

        /**
         * Retrieves the domain element corresponding to the specified DIMACS literal.
         * If <code>value</code> is negative, its sign is automatically flipped
         * before checking the internal table.
         * @param value
         * @return
         * @throws NoSuchElementException if the argument does not exist in the internal table
         * @throws IllegalArgumentException if the argument is 0
         */
        @Override
        public String fromPositiveLiteral(int value) {
            return internalTable.column(value).keySet().iterator().next();
        }

        /**
         * Retrieves the IRI string corresponding to the specified domain element.
         * @param domainElement
         * @return
         * @throws NoSuchElementException if the argument does not exist in the internal table
         */
        @Override
        public IRI getIRI(String domainElement) {
            Objects.requireNonNull(domainElement);
            return internalTable.row(domainElement).values().iterator().next();
        }

        /**
         * Returns the set of domain values.
         * @return
         */
        public Set<String> getDomainValues() {
            return internalTable.rowKeySet();
        }

        /**
         * Returns a stream containing the DIMACS literals stored in this <code>Table</code>.
         * The returned stream is equivalent to
         * <pre>{@code IntStream.rangeClosed(1, size()); }</pre>
         * @return
         */
        public IntStream getDimacsLiterals() {
            return IntStream.rangeClosed(1, size());
        }

        /**
         * Returns the set of <code>IRI</code>s.
         * @return
         */
        public Set<IRI> getIRISet() {
            return ImmutableSet.copyOf(internalTable.values());
        }

        /**
         * Returns the number of mappings in this table, which is also the highest DIMACS literal.
         * @return
         */
        public int size() {
            return internalTable.size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Table other = (Table) o;
            return internalTable.equals(other.internalTable);
        }

        @Override
        public int hashCode() {
            return internalTable.hashCode();
        }
    }

    /**
     * Creates <code>OntologicalCPNet</code> instances.
     */
    public static class Builder {
        // constants
        private static final String[] DISAMBIGUATION_SUFFIXES = {"", "_pref", "_user", "_aug"};
        // parameters for the OntologicalCPNet instance to build
        private CPNet baseCPNet;
        private OWLReasonerFactory reasonerFactory;
        // temporary variables for the building process
        private Set<String> domainValues;
        private OWLOntology baseOntology;
        private Map<String, OWLClassExpression> definitions;

        private Builder(CPNet baseCPNet, OWLOntology baseOntology) {
            domainValues = baseCPNet.getPreferenceGraph().domainValues()
                    .collect(Collectors.toSet());
            this.baseCPNet = baseCPNet;
            this.baseOntology = baseOntology;
            definitions = new HashMap<>();
            reasonerFactory = null;
        }

        /**
         * Sets the specified factory object for creating {@link OWLReasoner} instances.
         *
         * <p>The factory object is an optional parameter for the {@link OntologicalCPNet} to build.
         * If this method is not invoked before {@link #build()}, a default value will be used.
         * @param factory
         * @return
         * @throws IllegalStateException if a factory object was already set for this builder
         */
        public Builder withReasonerFactory(OWLReasonerFactory factory) {
            if (this.reasonerFactory != null) throw new IllegalStateException();
            this.reasonerFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * OWL class definitions are required parameters for the {@link OntologicalCPNet} to build.
         * This method must be invoked for each element
         * returned by <code>getPreferenceGraph().domainValues()</code>,
         * otherwise {@link #build()} will throw an {@link IllegalStateException}.
         * @param domainValue
         * @param definition
         * @return
         * @throws IllegalStateException if the specified <code>domainValue</code> is absent in the base CP-net
         * or already defined set this builder
         */
        public Builder addPreferenceDefinition(String domainValue, OWLClassExpression definition) {
            Objects.requireNonNull(domainValue);
            Objects.requireNonNull(definition);
            if (!domainValues.contains(domainValue)) {
                throw new IllegalStateException(String.format("Unknown domain value '%s'", domainValue));
            }
            if (definitions.putIfAbsent(domainValue, definition) != null) {
                throw new IllegalStateException(String.format("OWL class definition for '%s' already set", domainValue));
            }
            return this;
        }

        /**
         * Builds an {@link OntologicalCPNet} using the parameters set by this {@link Builder}.
         * The preference definitions, provided to this <code>Builder</code>
         * with invocations of {@link #addPreferenceDefinition(String, OWLClassExpression)},
         * are converted into OWL <em>class definition</em> axioms.
         * More specifically, for each call to
         * <pre>{@code addPreferenceDefinition(domainValue, definition)}</pre>
         * an OWL class <em>C</em> is created using <code>domainValue</code> as IRI suffix;
         * then, the OWL axiom
         * <pre>{@code EquivalentClasses(C, definition)}</pre>
         * is created.
         *
         * <p>Additionally, for each preference variable <em>A</em> in the base CP-net
         * with domain values <em>a1, a2, a3, ...</em> the OWL axiom
         * <pre>{@code DisjointUnion(owl:Thing, a1, a2, a3, ...)}</pre>
         * is created, in order to "close down the world" with respect to preferences.
         *
         * <p>The constrained ontology is created by copying the base ontology into a
         * local {@link OWLOntologyManager} and adding the new axioms.
         *
         * <p>This method fails if the invoking <code>Builder</code> is in an inconsistent state.
         * For detailed information on setting the required parameters, see the javadoc
         * of the remaining {@link Builder} methods.
         * @return
         * @throws OWLOntologyCreationException if the base ontology cannot be copied into
         * a local {@link OWLOntologyManager} to create the constrained ontology.
         * @throws IllegalStateException if the required parameters are not set properly
         */
        public OntologicalCPNet build() throws OWLOntologyCreationException {
            // Check required parameters.
            if (baseOntology == null) {
                throw new IllegalStateException("base ontology not set");
            }
            if (!domainValues.equals(definitions.keySet())) {
                throw new IllegalStateException("missing OWL definition for some domain values");
            }
            // Check optional parameters.
            if (reasonerFactory == null) {
                reasonerFactory = new ReasonerFactory();
            }
            // Check the base ontology for consistency.
            OWLReasoner reasoner = reasonerFactory.createReasoner(baseOntology);
            boolean isBaseConsistent = reasoner.isConsistent();
            reasoner.dispose();
            if (!isBaseConsistent) {
                throw new IllegalStateException("inconsistent base ontology");
            }
            return new OntologicalCPNet(this);
        }
    }

}
