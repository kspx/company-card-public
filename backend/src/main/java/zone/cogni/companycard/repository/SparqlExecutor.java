package zone.cogni.companycard.repository;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class SparqlExecutor {
  private SparqlExecutor() {}

  public static <T> List<T> select(Model model, String query, Function<QuerySolution, T> mapper) {
    try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
      return Iter.asStream(qe.execSelect())
                 .map(mapper)
                 .filter(Objects::nonNull)
                 .toList();
    }
  }

  public static Optional<RDFNode> getNode(QuerySolution solution, String variable) {
    return Optional.ofNullable(solution.get(variable));
  }

  public static String getString(QuerySolution solution, String variable) {
    return getNode(solution, variable).map(SparqlExecutor::nodeToString)
                                      .orElse(null);
  }

  public static String getStringOrDefault(QuerySolution solution, String variable, String defaultValue) {
    return getNode(solution, variable).map(SparqlExecutor::nodeToString)
                                      .orElse(defaultValue);
  }

  public static String getUri(QuerySolution solution, String variable) {
    return getNode(solution, variable)
      .filter(RDFNode::isResource)
      .map(node -> node.asResource()
                       .getURI())
      .orElse(null);
  }

  public static int getInt(QuerySolution solution, String variable, int defaultValue) {
    return getNode(solution, variable)
      .filter(RDFNode::isLiteral)
      .map(node -> node.asLiteral()
                       .getInt())
      .orElse(defaultValue);
  }

  public static boolean getBoolean(QuerySolution solution, String variable, boolean defaultValue) {
    return getNode(solution, variable)
      .filter(RDFNode::isLiteral)
      .map(node -> node.asLiteral()
                       .getBoolean())
      .orElse(defaultValue);
  }

  public static Object getValue(RDFNode node) {
    if (node == null) return null;
    if (node.isLiteral()) {
      Object javaValue = node.asLiteral()
                             .getValue();

      if (javaValue instanceof String || javaValue instanceof Number || javaValue instanceof Boolean) {
        return javaValue;
      }
      return node.asLiteral()
                 .getLexicalForm();
    }
    if (node.isResource()) return node.asResource()
                                      .getURI();
    return node.toString();
  }

  private static String nodeToString(RDFNode node) {
    if (node.isLiteral()) return node.asLiteral()
                                     .getString();
    if (node.isResource()) return node.asResource()
                                      .getURI();
    return node.toString();
  }
}
