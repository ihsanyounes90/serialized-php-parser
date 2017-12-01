/*
Copyright (c) 2007 Zsolt Sz�sz <zsolt at lorecraft dot com>

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.lorecraft.phparser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <p>
 * Deserializes a serialized PHP data structure into corresponding Java objects.
 * It supports the integer, float, boolean, string primitives that are mapped to
 * their Java equivalent, plus arrays that are parsed into <code>Map</code>
 * instances and objects that are represented by
 * {@link SerializedPhpParser.PhpObject} instances.
 * </p>
 * <p>
 * <p>
 * Example of use:
 * </p>
 * <p>
 * <pre>
 * 		String input = "O:8:"TypeName":1:{s:3:"foo";s:3:"bar";}";
 * 		SerializedPhpParser serializedPhpParser = new SerializedPhpParser(input);
 * 	Object result = serializedPhpParser.parse();
 * </pre>
 * <p>
 * <p>
 * The <code>result</code> object will be a <code>PhpObject</code> with the name
 * "TypeName" and the attribute "foo" = "bar".
 * </p>
 */
public class SerializedPhpParser {

    private final String input;

    private final int inputLength;

    private int index;

    private boolean assumeUTF8 = true;

    private final ArrayList<Object> refArray = new ArrayList<Object>();

    private Pattern acceptedAttributeNameRegex = null;

    public SerializedPhpParser(String input) {
        this.input = input;
        inputLength = input.length();
    }

    public SerializedPhpParser(String input, boolean assumeUTF8) {
        this.input = input;
        inputLength = input.length();
        this.assumeUTF8 = assumeUTF8;
    }

    public Object parse() throws SerializedPhpParserException {
        Object result = parseInternal(false);
        cleanup();
        return result;
    }

    private void cleanup() {
        refArray.clear();
    }

    private Object parseInternal(boolean isKey)
            throws SerializedPhpParserException {
        checkUnexpectedLength(index + 2);
        char type = input.charAt(index);

        switch (type) {
        case 'i':
            index += 2;
            return parseInt(isKey);
        case 'd':
            index += 2;
            return parseFloat(isKey);
        case 'b':
            index += 2;
            return parseBoolean();
        case 's':
            index += 2;
            return parseString(isKey);
        case 'a':
            index += 2;
            return parseArray();
        case 'O':
            index += 2;
            return parseObject();
        case 'N':
            index += 2;
            return NULL;
        case 'R':
            index += 2;
            return parseReference();
        default:
            throw new SerializedPhpParserException("Encountered unknown type [" + type + "]", index,
                    SerializedPhpParserException.UNKNOWN_TYPE);
        }
    }

    private Object parseReference() throws SerializedPhpParserException {
        int delimiter = input.indexOf(';', index);

        if (delimiter == -1) {
            throw new SerializedPhpParserException("Unexpected end of serialized Reference!", index,
                    SerializedPhpParserException.MISSING_DELIMITER_STRING);
        }
        checkUnexpectedLength(delimiter + 1);

        Integer refIndex = Integer.valueOf(input.substring(index, delimiter)) - 1;
        index = delimiter + 1;

        if ((refIndex + 1) > refArray.size()) {
            throw new SerializedPhpParserException("Out of range reference index: " + (refIndex + 1) + " !", index,
                    SerializedPhpParserException.OUT_OF_RANG_REFERENCE);
        }

        Object value = refArray.get(refIndex);
        refArray.add(value);

        return value;
    }

    private Object parseObject() throws SerializedPhpParserException {
        PhpObject phpObject = new PhpObject();
        refArray.add(phpObject);
        int strLen = readLength();
        checkUnexpectedLength(strLen);
        phpObject.name = input.substring(index, index + strLen);
        index = index + strLen + 2;
        int attrLen = readLength();

        for (int i = 0; i < attrLen; i++) {
            Object key = parseInternal(true);
            Object value = parseInternal(false);
            if (isAcceptedAttribute(key)) {
                phpObject.attributes.put(key, value);
            }
        }

        index++;
        return phpObject;
    }

    private Map<Object, Object> parseArray() throws SerializedPhpParserException {
        int arrayLen = readLength();
        checkUnexpectedLength(arrayLen);
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        refArray.add(result);

        for (int i = 0; i < arrayLen; i++) {
            Object key = parseInternal(true);
            Object value = parseInternal(false);
            if (isAcceptedAttribute(key)) {
                result.put(key, value);
            }
        }

        char endChar = input.charAt(index);
        if (endChar != '}') {
            throw new SerializedPhpParserException("Unexpected end of serialized Array, missing }!", index,
                    SerializedPhpParserException.MISSING_CLOSER_STRING);
        }

        index++;
        return result;
    }

    private boolean isAcceptedAttribute(Object key) {
        if (acceptedAttributeNameRegex == null) {
            return true;
        }
        if (!(key instanceof String)) {
            return true;
        }
        return acceptedAttributeNameRegex.matcher((String) key).matches();
    }

    private int readLength() throws SerializedPhpParserException {
        int delimiter = input.indexOf(':', index);

        if (delimiter == -1) {
            throw new SerializedPhpParserException("Missing delimiter after string, array or object length field!",
                    index, SerializedPhpParserException.MISSING_DELIMITER_STRING);
        }

        checkUnexpectedLength(delimiter + 2);
        int arrayLen = Integer.valueOf(input.substring(index, delimiter));
        index = delimiter + 2;
        return arrayLen;
    }

    /**
     * Assumes strings are utf8 encoded
     *
     * @return
     */
    private String parseString(boolean isKey) throws SerializedPhpParserException {
        int strLen = readLength();
        checkUnexpectedLength(strLen);

        int utfStrLen = 0;
        int byteCount = 0;
        int nextCharIndex = index + 1;

        while (byteCount < strLen) {

            if (nextCharIndex >= inputLength) {
                throw new SerializedPhpParserException("Unexpected end of String (" + strLen + ") at position " +
                        (nextCharIndex - index) + ", absolut position in Input String: " + nextCharIndex + ". The " +
                        "string: " + input.substring(index, nextCharIndex), nextCharIndex,
                        SerializedPhpParserException.TO_LONG_STRING);
            }
            if (assumeUTF8) {

                final int codepoint = input.codePointAt(nextCharIndex);

                byteCount += new String(Character.toChars(codepoint)).getBytes().length;
                nextCharIndex += Character.charCount(codepoint);
                utfStrLen += Character.charCount(codepoint);

            } else {
                byteCount++;
                nextCharIndex++;
                utfStrLen++;
            }
        }
        String value = input.substring(index, index + utfStrLen);

        if ((index + utfStrLen + 2) > inputLength || (index + utfStrLen) > inputLength) {
            throw new SerializedPhpParserException("Unexpected serialized string length!", index,
                    SerializedPhpParserException.TO_LONG_STRING);
        }

        String endString = input.substring(index + utfStrLen, index + utfStrLen + 2);

        if (!endString.equals("\";")) {
            throw new SerializedPhpParserException("Unexpected serialized string length!", index,
                    SerializedPhpParserException.TO_SHORT_STRING);
        }
        index = index + utfStrLen + 2;

        if (!isKey) {
            refArray.add(value);
        }
        return value;
    }

    private Boolean parseBoolean() throws SerializedPhpParserException {
        int delimiter = input.indexOf(';', index);

        if (delimiter == -1) {
            throw new SerializedPhpParserException("Unexpected end of serialized boolean!", index);
        }

        checkUnexpectedLength(delimiter + 1);
        String value = input.substring(index, delimiter);

        if (value.equals("1")) {
            value = "true";
        } else if (value.equals("0")) {
            value = "false";
        }

        index = delimiter + 1;
        refArray.add(Boolean.valueOf(value));

        return Boolean.valueOf(value);
    }

    private Double parseFloat(boolean isKey) throws SerializedPhpParserException {
        int delimiter = input.indexOf(';', index);

        if (delimiter == -1) {
            throw new SerializedPhpParserException("Unexpected end of serialized float!", index,
                    SerializedPhpParserException.MISSING_DELIMITER_STRING);
        }
        checkUnexpectedLength(delimiter + 1);
        String value = input.substring(index, delimiter);
        index = delimiter + 1;

        if (!isKey) {
            refArray.add(Double.valueOf(value));
        }

        return Double.valueOf(value);
    }

    private Long parseInt(boolean isKey) throws SerializedPhpParserException {
        int delimiter = input.indexOf(';', index);

        if (delimiter == -1) {
            throw new SerializedPhpParserException("Unexpected end of serialized integer!", index,
                    SerializedPhpParserException.MISSING_DELIMITER_STRING);
        }
        checkUnexpectedLength(delimiter + 1);
        String value = input.substring(index, delimiter);
        index = delimiter + 1;

        if (!isKey) {
            refArray.add(Long.valueOf(value));
        }

        return Long.valueOf(value);
    }

    public void setAcceptedAttributeNameRegex(String acceptedAttributeNameRegex) {
        this.acceptedAttributeNameRegex = Pattern.compile(acceptedAttributeNameRegex);
    }

    public static final Object NULL = new Object() {

        @Override
        public String toString() {
            return "NULL";
        }
    };

    /**
     * Represents an object that has a name and a map of attributes
     */
    public static class PhpObject {

        public String name;

        public Map<Object, Object> attributes = new HashMap<Object, Object>();

        @Override
        public String toString() {
            return "\"" + name + "\" : " + attributes.toString();
        }
    }

    private void checkUnexpectedLength(int newIndex) throws SerializedPhpParserException {
        if (index > inputLength || newIndex > inputLength) {
            throw new SerializedPhpParserException("Unexpected end of serialized Input!", index,
                    SerializedPhpParserException.TO_SHORT_INPUT_STRING);
        }
    }

}

