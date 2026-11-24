package com.privateinvest.crm.requirements;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Reads the client field map (EXT_CRM_Field_Map_MASTERContact.csv, kept verbatim under
 * src/test/resources/requirements) into rows. The file is the requirement, so the reader keeps every cell as the
 * client typed it and only normalises what a spreadsheet export adds: no-break spaces, padding and blank rows.
 */
public final class FieldMapCsv {

    public static final String RESOURCE = "/requirements/contact-field-map.csv";

    /** One requirement row. {@code line} is the 1-based line where the row starts, for traceable test names. */
    public record Row(int line, String object, String field, String fieldType, String required, String picklistValues,
                      String notes, String rule, String block, String businessUnit) {

        /** "Contact [Broker] / ACR (line 41)". */
        public String id() {
            return object + " / " + field + " (line " + line + ")";
        }

        /** The picklist values as the client listed them. Lists use ';' when a value holds a comma, else ','. */
        public List<String> values() {
            if (picklistValues.isBlank()) {
                return List.of();
            }
            String sep = picklistValues.contains(";") ? ";" : ",";
            return Arrays.stream(picklistValues.split(sep)).map(FieldMapCsv::clean).filter(s -> !s.isEmpty()).toList();
        }

        public String requiredLower() {
            return required.toLowerCase(Locale.ROOT);
        }
    }

    private FieldMapCsv() {
    }

    public static List<Row> load() {
        try (InputStream in = FieldMapCsv.class.getResourceAsStream(RESOURCE)) {
            Objects.requireNonNull(in, "Missing test resource " + RESOURCE);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }

    /** RFC 4180 rows: quoted cells may hold commas, doubled quotes and line breaks. The header row is skipped. */
    static List<Row> parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int rowStart = 1;
        boolean header = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                if (!header) {
                    addRow(rows, cells, rowStart);
                }
                header = false;
                cells = new ArrayList<>();
                line++;
                rowStart = line;
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            if (!header) {
                addRow(rows, cells, rowStart);
            }
        }
        return rows;
    }

    private static void addRow(List<Row> rows, List<String> cells, int line) {
        String object = cell(cells, 0);
        String field = cell(cells, 1);
        if (object.isEmpty() && field.isEmpty()) {
            return; // the export pads the sheet with empty rows
        }
        rows.add(new Row(line, object, field, cell(cells, 2), cell(cells, 3), cell(cells, 4), cell(cells, 5),
                cell(cells, 6), cell(cells, 7), cell(cells, 8)));
    }

    private static String cell(List<String> cells, int i) {
        return i < cells.size() ? clean(cells.get(i)) : "";
    }

    /** No-break spaces become spaces, runs of spaces collapse, and the cell is trimmed. Line breaks are kept. */
    static String clean(String s) {
        return s.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").trim();
    }
}
