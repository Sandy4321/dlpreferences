package it.poliba.sisinflab.dlpreferences;

import java.util.stream.Collectors;

abstract class AbstractConstraint implements Constraint {
    @Override
    public String toString() {
        String leftSide = left().entrySet().stream()
                .map(entry -> entry.getValue() ?
                        entry.getKey() :
                        String.format("not(%s)", entry.getKey()))
                .collect(Collectors.joining(" AND "));
        String rightSide = right().entrySet().stream()
                .map(entry -> entry.getValue() ?
                        entry.getKey() :
                        String.format("not(%s)", entry.getKey()))
                .collect(Collectors.joining(" OR "));
        if (leftSide.isEmpty()) {
            return rightSide;
        }
        return String.format("%s IMPLIES %s", leftSide, rightSide);
    }
}
