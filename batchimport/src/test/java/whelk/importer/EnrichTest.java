package whelk.importer;

import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Assert;
import org.junit.*;

import java.io.IOException;
import java.util.*;
import whelk.triples.*;

@SuppressWarnings("unchecked")
public class EnrichTest
{
    private static final ObjectMapper s_mapper = new ObjectMapper();

    private static final String MAIN_ID = "main id";

    private void testEnrich(String jsonldOriginal, String jsonldIncoming,
                            String jsonldExpectedResult,
                            Map<String, Graph.PREDICATE_RULES> specialRules)
            throws IOException
    {
        Map originalData = s_mapper.readValue(jsonldOriginal, HashMap.class);
        Map incomingData = s_mapper.readValue(jsonldIncoming, HashMap.class);
        Map expectedResultData = s_mapper.readValue(jsonldExpectedResult, HashMap.class);

        JsonldSerializer serializer = new JsonldSerializer();
        List<String[]> originalTriples = serializer.deserialize(originalData);
        List<String[]> otherTriples = serializer.deserialize(incomingData);

        Graph originalGraph = new Graph(originalTriples);
        Graph otherGraph = new Graph(otherTriples);

        originalGraph.enrichWith(otherGraph, specialRules);

        Map enrichedData = JsonldSerializer.serialize(originalGraph.getTriples(), new HashSet<>());
        boolean deleteUnreferencedData = false;
        JsonldSerializer.normalize(enrichedData, MAIN_ID, deleteUnreferencedData);

        if ( ! rdfEquals( enrichedData, expectedResultData ) )
        {
            System.out.println("not equal:");
            System.out.println("enriched result:\n"+enrichedData);
            System.out.println("expected result:\n"+expectedResultData);
            Assert.assertTrue( false );
        }
    }

    private boolean rdfEquals(Map m1, Map m2)
    {
        if (m1.size() != m2.size())
            return false;

        for (Object key : m1.keySet())
        {
            Object value = m1.get(key);

            if (!m2.containsKey(key))
                return false;

            if (value instanceof Map)
            {
                if ( ! rdfEquals( (Map) value, (Map) m2.get(key)) )
                    return false;
            }
            else if (value instanceof List)
            {
                if ( ! rdfEquals( (List) value, (List) m2.get(key)) )
                    return false;
            }
            else if ( ! value.equals(m2.get(key)) )
                return false;
        }

        return true;
    }

    private boolean rdfEquals(List l1, List l2)
    {
        if (l1.size() != l2.size())
            return false;

        Set s1 = new HashSet(l1);
        Set s2 = new HashSet(l2);

        for (Object entry1 : s1)
        {
            boolean existsAMatch = false;
            for (Object entry2 : s2)
            {
                if (entry1 instanceof Map && entry2 instanceof Map)
                {
                    if ( rdfEquals( (Map) entry1, (Map) entry2) )
                        existsAMatch = true;
                }
                else if (entry1 instanceof List && entry2 instanceof List)
                {
                    if ( rdfEquals( (List) entry1, (List) entry2) )
                        existsAMatch = true;
                }
                else if (entry1 instanceof String && entry2 instanceof String)
                {
                    if ( entry1.equals(entry2) )
                        existsAMatch = true;
                }
            }
            if (!existsAMatch)
                return false;
        }

        return true;
    }


    @Test
    public void identity() throws Exception
    {
        // Enriching a graph with itself must not change the graph

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"bnode subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();

        testEnrich(original, original, original, specialRules);
    }

    @Test
    public void extraGraphListObject() throws Exception
    {
        // Adding another object in the @graph list. Otherwise identical

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"bnode subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"bnode subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        },\n" +
                "\t{\n" +
                "            \"@id\": \"other id\",\n" +
                "            \"bnode subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, incoming, specialRules);
    }

    @Test
    public void testPreferOriginalAsDefault() throws Exception
    {
        // Adding another object in the @graph list. Otherwise identical

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"some literal\": \"original value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"some literal\": \"incoming value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, original, specialRules);
    }

    @Test
    public void testExplicitRuleAggregate() throws Exception
    {
        // Adding another object in the @graph list. Otherwise identical

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"some literal\": \"original value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"some literal\": \"incoming value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String expectedResult = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"some literal\": [" +
                "                \"original value\",\n" +
                "                \"incoming value\"\n" +
                "            ]" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        specialRules.put("some literal", Graph.PREDICATE_RULES.RULE_AGGREGATE);
        testEnrich(original, incoming, expectedResult, specialRules);
    }

    @Test
    public void extraInfoInBNode() throws Exception
    {
        // Adding an extra key value pair in a BNode

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"bnode subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"bnode subobject\": {\n" +
                "                \"other literal\": \"other value\",\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, incoming, specialRules);
    }

    @Test
    public void extraInfoNestedBNode() throws Exception
    {
        // Adding an extra key value pair in a BNode, containing another nested BNode that must be matched

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"some subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        },\n" +
                "        {\n" +
                "            \"this bnode\": \"should not match\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"other literal\": \"other value\",\n" +
                "            \"some subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String expectedResult = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"other literal\": \"other value\",\n" +
                "            \"some subobject\": {\n" +
                "                \"some literal\": \"some value\"\n" +
                "            }\n" +
                "        },\n" +
                "        {\n" +
                "            \"this bnode\": \"should not match\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, expectedResult, specialRules);
    }

    @Test
    public void combinedNamedNode() throws Exception
    {
        // Combining properties of two named nodes with the same name

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"some literal\" : \"some value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"other literal\" : \"other value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String expectedResult = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"some id\",\n" +
                "            \"other literal\": \"other value\",\n" +
                "            \"some literal\": \"some value\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, expectedResult, specialRules);
    }

    @Test
    public void nonmatchingBNode() throws Exception
    {
        // Merging two graphs with one BNode each which can't be determined to represent the same thing.

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"this bnode\": \"should not match\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"with\": \"this bnode\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String expectedResult = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"with\": \"this bnode\"\n" +
                "        },\n" +
                "        {\n" +
                "            \"this bnode\": \"should not match\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, expectedResult, specialRules);
    }

    @Test
    public void valueTypes() throws Exception
    {
        // Enriching must work with literal values of type String, int, float, boolean

        String original = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"someid\",\n" +
                "            \"somebool\": true,\n" +
                "            \"somefloat\": 4.0,\n" +
                "            \"someint\": 3,\n" +
                "            \"somestring\": \"string\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String incoming = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"someid\",\n" +
                "            \"someotherbool\": false,\n" +
                "            \"someotherfloat\": 5.0,\n" +
                "            \"someotherint\": 7,\n" +
                "            \"someotherstring\": \"otherstring\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        String expectedResult = "{\n" +
                "    \"@graph\": [\n" +
                "        {\n" +
                "            \"@id\": \"someid\",\n" +
                "            \"somebool\": true,\n" +
                "            \"somefloat\": 4.0,\n" +
                "            \"someint\": 3,\n" +
                "            \"someotherbool\": false,\n" +
                "            \"someotherfloat\": 5.0,\n" +
                "            \"someotherint\": 7,\n" +
                "            \"someotherstring\": \"otherstring\",\n" +
                "            \"somestring\": \"string\"\n" +
                "        }\n" +
                "    ]\n" +
                "}\n";

        Map<String, Graph.PREDICATE_RULES> specialRules = new HashMap<>();
        testEnrich(original, incoming, expectedResult, specialRules);
    }
}
