package whelk.export.servlet;

import whelk.Document;

import javax.servlet.http.HttpServletRequest;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.StringJoiner;

public class Helpers
{
    public static String getUnknownParameters(HttpServletRequest request, String... knownParameters)
    {
        HashSet<String> knownParametersSet = new HashSet<String>();
        knownParametersSet.addAll(Arrays.asList(knownParameters));

        StringJoiner unknownParameters = new StringJoiner(", ");
        Enumeration parameterNames = request.getParameterNames();
        while (parameterNames.hasMoreElements())
        {
            String parameterName = (String) parameterNames.nextElement();
            if (!knownParametersSet.contains(parameterName) && !parameterName.equals("verb"))
            {
                unknownParameters.add(parameterName);
            }
        }

        if (unknownParameters.length() == 0)
            return null;
        return unknownParameters.toString();
    }

    public static ZonedDateTime parseISO8601(String dateTimeString)
    {
        if (dateTimeString == null)
            return null;
        if (dateTimeString.length() == 10) // Date only
            dateTimeString += "T00:00:00Z";
        return ZonedDateTime.parse(dateTimeString);
    }

    public static String getShorthandDocumentId(String completeId)
    {
        String idPrefix = Document.getBASE_URI().toString();
        if (!completeId.startsWith(idPrefix))
            return null;
        return completeId.substring(idPrefix.length());
    }

    /**
     * Obviously parametrized prepared statements are best. But Postgres JSONB fields can't be parameterized using
     * normal means, so this hack becomes an unfortunate necessity.
     */
    private final static HashSet<Character> s_allowedChars;
    static
    {
        char[] allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖabcdefghijklmnopqrstuvwxyzåäö0123456789".toCharArray();
        s_allowedChars = new HashSet<Character>();
        for (char c : allowedChars)
            s_allowedChars.add(c);
    }
    public static String scrubSQL(String unsafeSql)
    {
        StringBuilder scrubbed = new StringBuilder("");
        for (int i = 0; i < unsafeSql.length(); ++i)
        {
            char c = unsafeSql.charAt(i);
            if ( s_allowedChars.contains( c ) )
                scrubbed.append( c );
        }
        return  scrubbed.toString();
    }
}