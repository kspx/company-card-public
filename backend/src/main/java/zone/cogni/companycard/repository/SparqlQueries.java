package zone.cogni.companycard.repository;

public final class SparqlQueries {
    private SparqlQueries() {
    }

    public static String findClasses(String ontologyNamespace) {
        return """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                SELECT ?class ?label ?comment WHERE {
                    ?class a owl:Class .
                    OPTIONAL { ?class rdfs:label ?label . }
                    OPTIONAL { ?class rdfs:comment ?comment . }
                    FILTER(STRSTARTS(STR(?class), "%s"))
                }
                ORDER BY ?label
                """.formatted(ontologyNamespace);
    }

    public static String findFormFieldsFromShapes(String classUri) {
        return """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT ?prop ?name ?description ?datatype ?class ?minCount ?maxCount ?order ?createOnly ?noCreate ?conceptScheme ?computed
                WHERE {
                    ?shape sh:targetClass <%s> ;
                           sh:property ?propShape .
                    ?propShape sh:path ?prop .
                    OPTIONAL { ?propShape sh:name ?name . FILTER(LANG(?name) = "en" || LANG(?name) = "") }
                    OPTIONAL { ?propShape sh:description ?description . FILTER(LANG(?description) = "en" || LANG(?description) = "") }
                    OPTIONAL { ?propShape sh:datatype ?datatype . }
                    OPTIONAL { ?propShape sh:class ?class . }
                    OPTIONAL { ?propShape sh:minCount ?minCount . }
                    OPTIONAL { ?propShape sh:maxCount ?maxCount . }
                    OPTIONAL { ?propShape sh:order ?order . }
                    OPTIONAL { ?propShape cc:createOnly ?createOnly . }
                    OPTIONAL { ?propShape cc:noCreate ?noCreate . }
                    OPTIONAL { ?propShape cc:conceptScheme ?conceptScheme . }
                    OPTIONAL { ?propShape cc:computed ?computed . }
                }
                ORDER BY COALESCE(?order, 9999)
                """.formatted(validateUri(classUri));
    }

    public static String findPropertyUrisFromShapes(String classUri) {
        return """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                SELECT DISTINCT ?prop WHERE {
                    ?shape sh:targetClass <%s> ;
                           sh:property [ sh:path ?prop ] .
                }
                """.formatted(validateUri(classUri));
    }

    public static String findDirectInverseProperties(String classUri) {
        return """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                SELECT ?prop ?inverseProp WHERE {
                    ?prop rdfs:domain <%s> ;
                          owl:inverseOf ?inverseProp .
                }
                """.formatted(validateUri(classUri));
    }

    public static String findReverseInverseProperties(String classUri) {
        return """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                SELECT ?prop ?inverseProp WHERE {
                    ?prop rdfs:domain <%s> .
                    ?inverseProp owl:inverseOf ?prop .
                }
                """.formatted(validateUri(classUri));
    }

    public static String findInverseOfProperty(String propertyUri) {
        return """
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                SELECT ?inverse WHERE {
                    { <%s> owl:inverseOf ?inverse . }
                    UNION
                    { ?inverse owl:inverseOf <%s> . }
                }
                """.formatted(validateUri(propertyUri), validateUri(propertyUri));
    }

    public static String findPropertyConstraints(String classUri) {
        return """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                SELECT ?prop ?minCount ?maxCount ?datatype ?pattern ?message WHERE {
                    ?shape sh:targetClass <%s> ;
                           sh:property ?propShape .
                    ?propShape sh:path ?prop .
                    OPTIONAL { ?propShape sh:minCount ?minCount . }
                    OPTIONAL { ?propShape sh:maxCount ?maxCount . }
                    OPTIONAL { ?propShape sh:datatype ?datatype . }
                    OPTIONAL { ?propShape sh:pattern ?pattern . }
                    OPTIONAL { ?propShape sh:message ?message . }
                    FILTER (BOUND(?minCount) || BOUND(?maxCount) || BOUND(?datatype) || BOUND(?pattern))
                }
                """.formatted(validateUri(classUri));
    }

    public static String findExclusiveProperties() {
        return """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                SELECT DISTINCT ?prop WHERE {
                    ?shape sh:property ?propShape .
                    ?propShape sh:path ?prop ;
                               sh:maxCount 1 .
                }
                """;
    }

    public static String findSubPropertyImplications(String ontologyNamespace) {
        return """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT ?sub ?super WHERE {
                    ?sub rdfs:subPropertyOf ?super .
                    FILTER(STRSTARTS(STR(?sub), "%s"))
                    FILTER(STRSTARTS(STR(?super), "%s"))
                }
                """.formatted(ontologyNamespace, ontologyNamespace);
    }

    public static String findGraphEdges(String ontologyNamespace) {
        return """
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT DISTINCT ?subject ?predicate ?object WHERE {
                    ?subject rdf:type ?sType .
                    FILTER(STRSTARTS(STR(?sType), "%s"))
                    ?subject ?predicate ?object .
                    FILTER(isURI(?object))
                    ?object rdf:type ?oType .
                    FILTER(STRSTARTS(STR(?oType), "%s"))
                    FILTER(?predicate != rdf:type)
                }
                """.formatted(ontologyNamespace, ontologyNamespace);
    }

    public static String findIdentifierProperties(String classUri) {
        return """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?prop ?order WHERE {
                    <%s> rdfs:subClassOf* ?targetClass .
                    ?shape sh:targetClass ?targetClass ;
                           sh:property ?propShape .
                    ?propShape sh:path ?prop ;
                               cc:identifierPart true .
                    OPTIONAL { ?propShape sh:order ?order . }
                }
                ORDER BY COALESCE(?order, 9999)
                """.formatted(validateUri(classUri));
    }

    public static String findConceptSchemeForProperty(String propertyUri) {
        return """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT ?scheme WHERE {
                    ?shape sh:property ?propShape .
                    ?propShape sh:path <%s> ;
                               cc:conceptScheme ?scheme .
                }
                LIMIT 1
                """.formatted(validateUri(propertyUri));
    }

    public static String findProjectByRepositoryUrl(String repoUrl) {
        return """
                PREFIX cc:   <https://ontology.cogni.zone/company-card#>
                PREFIX doap: <http://usefulinc.com/ns/doap#>
                PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?project WHERE {
                    ?project rdf:type cc:Project ;
                             doap:repository "%s"^^<http://www.w3.org/2001/XMLSchema#anyURI> .
                }
                LIMIT 1
                """.formatted(escapeLiteral(repoUrl));
    }

    public static String findPersonByGithubLogin(String login) {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX ccv:     <https://ontology.cogni.zone/company-card-vocabularies#>
                PREFIX adms:    <http://www.w3.org/ns/adms#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?person WHERE {
                    ?person rdf:type cc:Person ;
                            adms:identifier ?id .
                    ?id dcterms:type ccv:GITHUB ;
                        skos:notation "%s" .
                }
                LIMIT 1
                """.formatted(escapeLiteral(login));
    }

    public static String findPersonBySlackId(String slackId) {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX ccv:     <https://ontology.cogni.zone/company-card-vocabularies#>
                PREFIX adms:    <http://www.w3.org/ns/adms#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?person WHERE {
                    ?person rdf:type cc:Person ;
                            adms:identifier ?id .
                    ?id dcterms:type ccv:SLACK ;
                        skos:notation "%s" .
                }
                LIMIT 1
                """.formatted(escapeLiteral(slackId));
    }

    public static String findAllPersonsWithSlackId() {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX ccv:     <https://ontology.cogni.zone/company-card-vocabularies#>
                PREFIX adms:    <http://www.w3.org/ns/adms#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?person ?slackId WHERE {
                    ?person rdf:type cc:Person ;
                            adms:identifier ?id .
                    ?id dcterms:type ccv:SLACK ;
                        skos:notation ?slackId .
                }
                """;
    }

    private static String escapeLiteral(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private static String validateUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("URI must not be null or empty");
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c == '<' || c == '>' || c == '"' || c == '{' || c == '}'
                || c == '|' || c == '\\' || c == '^' || c == '`') {
                throw new IllegalArgumentException("Invalid character in URI: " + uri);
            }
        }
        return uri;
    }

    public static String findAllPersonsWithGithubUsername() {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX ccv:     <https://ontology.cogni.zone/company-card-vocabularies#>
                PREFIX adms:    <http://www.w3.org/ns/adms#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?person ?username WHERE {
                    ?person rdf:type cc:Person ;
                            adms:identifier ?ghId .
                    ?ghId dcterms:type ccv:GITHUB ;
                          skos:notation ?username .
                }
                """;
    }

    public static String findAllLinkedRepositories(String orgBaseUrl) {
        return """
                PREFIX cc:   <https://ontology.cogni.zone/company-card#>
                PREFIX doap: <http://usefulinc.com/ns/doap#>
                PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?project ?repoUrl WHERE {
                    ?project rdf:type cc:Project ;
                             doap:repository ?repoUrl .
                    FILTER(STRSTARTS(STR(?repoUrl), "%s"))
                }
                """.formatted(escapeLiteral(orgBaseUrl));
    }

    public static String findContributionByPersonAndProject(String personUri, String projectUri) {
        return """
                PREFIX cc:  <https://ontology.cogni.zone/company-card#>
                SELECT ?contribution WHERE {
                    ?contribution cc:contributionBy <%s> ;
                                  cc:contributionTo <%s> .
                }
                LIMIT 1
                """.formatted(validateUri(personUri), validateUri(projectUri));
    }

    public static String findEarliestContributionStartDateForTeamMember(String personUri, String teamUri) {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT (MIN(?start) AS ?startDate) WHERE {
                    <%s> cc:developsProject ?project .
                    ?contrib cc:contributionBy <%s> ;
                             cc:contributionTo ?project ;
                             schema:startDate ?start .
                }
                """.formatted(validateUri(teamUri), validateUri(personUri));
    }

    public static String findProjectContributors() {
        return """
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT DISTINCT ?project ?person WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project .
                }
                """;
    }

    public static String findProjectClients() {
        return """
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT DISTINCT ?project ?client WHERE {
                    ?project a cc:Project ;
                             cc:requestedBy ?client .
                }
                """;
    }

    public static String findEarliestContributionDatePerPerson() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT ?person (MIN(?start) AS ?startDate) WHERE {
                    ?contrib cc:contributionBy ?person ;
                             schema:startDate ?start .
                }
                GROUP BY ?person
                """;
    }

    public static String findEarliestContributionDatePerPersonProject() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT ?person ?project (MIN(?start) AS ?startDate) WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project ;
                             schema:startDate ?start .
                }
                GROUP BY ?person ?project
                """;
    }

    public static String findLatestContributionDatePerPersonProject() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT ?person ?project (MAX(?activity) AS ?endDate) WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project ;
                             schema:startDate ?start .
                    OPTIONAL { ?contrib schema:endDate ?end . }
                    BIND(COALESCE(?end, ?start) AS ?activity)
                }
                GROUP BY ?person ?project
                """;
    }

    public static String findAllTeamMembershipPairs() {
        return """
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT ?person ?team WHERE {
                    ?membership a cc:TeamMembership ;
                                cc:membershipOf ?person ;
                                cc:membershipIn ?team .
                }
                """;
    }

    public static String storyTopContributors() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT ?person
                       (SAMPLE(?gn)  AS ?givenName)
                       (SAMPLE(?fn)  AS ?familyName)
                       (COALESCE(SUM(?commits),  0) AS ?totalCommits)
                       (COALESCE(SUM(?lines),    0) AS ?totalLinesAdded)
                       (COALESCE(SUM(?prs),      0) AS ?totalMergedPrs)
                       (COALESCE(SUM(?reviews),  0) AS ?totalReviews)
                       (COUNT(DISTINCT ?contrib)    AS ?projectCount)
                WHERE {
                    ?person a cc:Person .
                    OPTIONAL { ?person schema:givenName  ?gn }
                    OPTIONAL { ?person schema:familyName ?fn }
                    ?contrib cc:contributionBy ?person .
                    OPTIONAL { ?contrib cc:commitCount   ?commits }
                    OPTIONAL { ?contrib cc:linesAdded    ?lines   }
                    OPTIONAL { ?contrib cc:mergedPrCount ?prs     }
                    OPTIONAL { ?contrib cc:prReviewCount ?reviews }
                }
                GROUP BY ?person
                ORDER BY DESC(?totalCommits)
                """;
    }

    public static String storyCrossClientVersatility() {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX schema:  <https://schema.org/>
                PREFIX rov:     <http://www.w3.org/ns/regorg#>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                SELECT ?person
                       (SAMPLE(?gn) AS ?givenName)
                       (SAMPLE(?fn) AS ?familyName)
                       (COUNT(DISTINCT ?org)     AS ?clientCount)
                       (COUNT(DISTINCT ?project) AS ?projectCount)
                       (GROUP_CONCAT(DISTINCT ?nm ; SEPARATOR = ", ") AS ?clients)
                WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project .
                    ?project cc:requestedBy ?org .
                    OPTIONAL { ?person schema:givenName  ?gn }
                    OPTIONAL { ?person schema:familyName ?fn }
                    OPTIONAL { ?org rov:legalName     ?ln }
                    OPTIONAL { ?org skos:altLabel      ?al }
                    OPTIONAL { ?org dcterms:identifier ?id }
                    BIND(COALESCE(?ln, ?al, ?id, STR(?org)) AS ?nm)
                }
                GROUP BY ?person
                ORDER BY DESC(?clientCount) DESC(?projectCount)
                """;
    }

    public static String storyEmployeeJourney() {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX schema:  <https://schema.org/>
                PREFIX doap:    <http://usefulinc.com/ns/doap#>
                PREFIX rov:     <http://www.w3.org/ns/regorg#>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                SELECT ?person
                       (SAMPLE(?gn) AS ?givenName)
                       (SAMPLE(?fn) AS ?familyName)
                       ?project
                       (SAMPLE(?pn) AS ?projectName)
                       ?org
                       (SAMPLE(?nm) AS ?clientName)
                       (MIN(?start)    AS ?startDate)
                       (MAX(?activity) AS ?endDate)
                       (SUM(?commits)  AS ?commitCount)
                WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project ;
                             schema:startDate  ?start .
                    ?project cc:requestedBy ?org .
                    OPTIONAL { ?project doap:name        ?pn }
                    OPTIONAL { ?person  schema:givenName  ?gn }
                    OPTIONAL { ?person  schema:familyName ?fn }
                    OPTIONAL { ?org rov:legalName     ?ln }
                    OPTIONAL { ?org skos:altLabel      ?al }
                    OPTIONAL { ?org dcterms:identifier ?id }
                    BIND(COALESCE(?ln, ?al, ?id, STR(?org)) AS ?nm)
                    OPTIONAL { ?contrib cc:lastCommitDate ?last }
                    OPTIONAL { ?contrib cc:commitCount    ?c }
                    BIND(COALESCE(?last, ?start) AS ?activity)
                    BIND(COALESCE(?c, 0)         AS ?commits)
                }
                GROUP BY ?person ?project ?org
                ORDER BY ?person ?startDate
                """;
    }

    public static String storyDeliveryRisk() {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX schema:  <https://schema.org/>
                PREFIX rov:     <http://www.w3.org/ns/regorg#>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                SELECT ?org
                       (SAMPLE(?nm) AS ?clientName)
                       ?discipline
                       ?person
                       (SAMPLE(?gn) AS ?givenName)
                       (SAMPLE(?fn) AS ?familyName)
                       (COALESCE(SUM(?c), 0) AS ?commitCount)
                WHERE {
                    VALUES (?dteam ?discipline) {
                        (<https://data.cogni.zone/Team/backend>  "Backend")
                        (<https://data.cogni.zone/Team/frontend> "Frontend")
                    }
                    {
                        SELECT ?org (MAX(?d) AS ?lastActivity) WHERE {
                            ?anyContrib cc:contributionTo ?anyProject ;
                                        cc:lastCommitDate ?d .
                            ?anyProject cc:requestedBy ?org .
                        }
                        GROUP BY ?org
                    }
                    ?membership cc:membershipOf ?person ;
                                cc:membershipIn ?dteam .
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project .
                    ?project cc:requestedBy ?org .
                    OPTIONAL { ?contrib cc:lastCommitDate ?lcd }
                    OPTIONAL { ?contrib schema:startDate   ?sd }
                    BIND(COALESCE(?lcd, ?sd) AS ?cdate)
                    FILTER(BOUND(?cdate) && YEAR(?cdate) >= YEAR(?lastActivity) - 3)
                    OPTIONAL { ?person schema:givenName  ?gn }
                    OPTIONAL { ?person schema:familyName ?fn }
                    OPTIONAL { ?org rov:legalName     ?ln }
                    OPTIONAL { ?org skos:altLabel      ?al }
                    OPTIONAL { ?org dcterms:identifier ?id }
                    BIND(COALESCE(?ln, ?al, ?id, STR(?org)) AS ?nm)
                    OPTIONAL { ?contrib cc:commitCount ?c }
                }
                GROUP BY ?org ?discipline ?person
                ORDER BY ?org ?discipline DESC(?commitCount)
                """;
    }

    public static String storyMeetTheTeam() {
        return """
                PREFIX cc:      <https://ontology.cogni.zone/company-card#>
                PREFIX schema:  <https://schema.org/>
                PREFIX rov:     <http://www.w3.org/ns/regorg#>
                PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                SELECT ?org
                       (SAMPLE(?nm) AS ?clientName)
                       ?person
                       (SAMPLE(?gn) AS ?givenName)
                       (SAMPLE(?fn) AS ?familyName)
                       (COALESCE(SUM(?c), 0) AS ?commitCount)
                       (COALESCE(SUM(?r), 0) AS ?reviewCount)
                       (COUNT(DISTINCT ?project) AS ?projectCount)
                       (MIN(?start)    AS ?firstActivity)
                       (MAX(?activity) AS ?lastActivity)
                WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project .
                    ?project cc:requestedBy ?org .
                    OPTIONAL { ?person schema:givenName  ?gn }
                    OPTIONAL { ?person schema:familyName ?fn }
                    OPTIONAL { ?org rov:legalName     ?ln }
                    OPTIONAL { ?org skos:altLabel      ?al }
                    OPTIONAL { ?org dcterms:identifier ?id }
                    BIND(COALESCE(?ln, ?al, ?id, STR(?org)) AS ?nm)
                    OPTIONAL { ?contrib cc:commitCount    ?c }
                    OPTIONAL { ?contrib cc:prReviewCount  ?r }
                    OPTIONAL { ?contrib cc:lastCommitDate ?last }
                    OPTIONAL { ?contrib schema:startDate  ?start }
                    BIND(COALESCE(?last, ?start) AS ?activity)
                }
                GROUP BY ?org ?person
                ORDER BY ?org DESC(?commitCount)
                """;
    }

    public static String findOwnOrganization() {
        return """
                PREFIX cc:    <https://ontology.cogni.zone/company-card#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX ccv:   <https://ontology.cogni.zone/company-card-vocabularies#>
                SELECT ?org WHERE {
                    ?org a cc:Organization ;
                         dcterms:source ccv:SLACK .
                }
                LIMIT 1
                """;
    }

    public static String findClientOrgsWithEarliestDate() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                PREFIX doap:   <http://usefulinc.com/ns/doap#>
                SELECT ?clientOrg (MIN(?date) AS ?startDate) WHERE {
                    ?project a cc:Project ;
                             cc:requestedBy ?clientOrg .
                    {
                        ?contrib cc:contributionTo ?project ;
                                 schema:startDate ?date .
                    } UNION {
                        ?project doap:created ?date .
                    }
                }
                GROUP BY ?clientOrg
                HAVING (BOUND(?startDate))
                ORDER BY ?clientOrg
                """;
    }

    public static String findPersonClientOrgs() {
        return """
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT DISTINCT ?person ?clientOrg WHERE {
                    ?contrib cc:contributionBy ?person ;
                             cc:contributionTo ?project .
                    ?project cc:requestedBy ?clientOrg .
                }
                """;
    }

    public static String findProjectsForStatus() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT ?project ?archived (MAX(?modified) AS ?lastActivity) WHERE {
                    ?project a cc:Project .
                    OPTIONAL { ?project cc:isArchived ?archived . }
                    OPTIONAL { ?project schema:dateModified ?modified . }
                }
                GROUP BY ?project ?archived
                """;
    }

    public static String findPersonsForAvailability() {
        return """
                PREFIX cc: <https://ontology.cogni.zone/company-card#>
                SELECT ?person (MAX(?last) AS ?lastActivity) (SAMPLE(?st) AS ?status) WHERE {
                    ?person a cc:Person .
                    OPTIONAL {
                        ?contrib cc:contributionBy ?person ;
                                 cc:lastCommitDate ?last .
                    }
                    OPTIONAL { ?person cc:availabilityStatus ?st . }
                }
                GROUP BY ?person
                """;
    }

    public static String findEngagementsForLifecycle() {
        return """
                PREFIX cc:     <https://ontology.cogni.zone/company-card#>
                PREFIX schema: <https://schema.org/>
                SELECT ?engagement (MAX(?pushed) AS ?lastActivity) WHERE {
                    ?engagement a cc:Engagement ;
                                cc:engagementWith ?clientOrg .
                    OPTIONAL {
                        ?project cc:requestedBy ?clientOrg ;
                                 schema:dateModified ?pushed .
                    }
                }
                GROUP BY ?engagement
                """;
    }

    public static String findConceptsInScheme(String schemeUri) {
        return """
                PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
                SELECT ?concept ?label (SAMPLE(?n) AS ?notation) WHERE {
                    ?concept skos:inScheme <%s> ;
                             skos:prefLabel ?label .
                    OPTIONAL { ?concept skos:notation ?n . }
                    FILTER(LANG(?label) = "en" || LANG(?label) = "")
                }
                GROUP BY ?concept ?label
                ORDER BY ?label
                """.formatted(validateUri(schemeUri));
    }
}
