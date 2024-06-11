/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.igvutil.args;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.collections.EconomicMap;

/**
 * Contains utilities to parse a set of named from command-line arguments.
 * A command is made up of positional and named named, as well as a single optional {@link CommandGroup}.
 * Named named can appear at any index in the arguments, and must be prefixed by their name.
 * Positional named must appear in the same order as they were added to the command, and they don't require being prefixed by a name.
 *
 * @see #addNamed(String, OptionValue)
 * @see #addPositional(OptionValue)
 */
public class Command {
    /**
     * Used to disambiguate where one (sub-)command ends.
     */
    public static final String SEPARATOR = "--";

    /**
     * The value of a named option argument may be specified as a successive program argument
     * (e.g. --arg value) or with an equal sign (e.g. --arg=value).
     */
    public static final char EQUAL_SIGN = '=';

    /**
     * Help flag as specified on the command-line.
     */
    public static final String HELP = "--help";

    /**
     * Map of argument names to named arguments.
     */
    private final EconomicMap<String, OptionValue<?>> named = EconomicMap.create();

    /**
     * List of positional arguments.
     */
    private final List<OptionValue<?>> positional = new ArrayList<>();

    /**
     * Set of subcommands. May be null.
     */
    private CommandGroup<? extends Command> commandGroup = null;

    private final String name;
    private final String description;

    public Command(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /**
     * Appends an option to the list of positional options.
     *
     * @return the value of {@code argument}. New instantiations of {@link OptionValue} should always be wrapped by a call to this function or {@link #addNamed(String, OptionValue)}.
     */
    public <T> OptionValue<T> addPositional(OptionValue<T> argument) {
        positional.add(argument);
        return argument;
    }

    /**
     * Adds an option to the set of named options.
     *
     * @return the value of {@code argument}. New instantiations of {@link OptionValue} should always be wrapped by a call to this function or {@link #addPositional(OptionValue)}}.
     */
    public <T> OptionValue<T> addNamed(String optionName, OptionValue<T> argument) {
        named.put(optionName, argument);
        return argument;
    }

    /**
     * Sets the command's subcommand group.
     *
     * @return the value of {@code commandGroup}. New instantiations of {@link CommandGroup} should always be wrapped by a call to this function.
     */
    public <C extends Command> CommandGroup<C> addCommandGroup(CommandGroup<C> commandGroup) {
        if (this.commandGroup != null) {
            throw new RuntimeException("Only one subcommand per command is supported");
        }
        this.commandGroup = commandGroup;
        return commandGroup;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Parses an argument of the form "--option=value".
     *
     * @param arg            the argument in question.
     * @param equalSignIndex index of the equals sign in {@code arg}.
     * @return true iff the argument was parsed successfully.
     */
    private boolean parseEqualsValue(String arg, int equalSignIndex) throws InvalidArgumentException {
        String optionName = arg.substring(0, equalSignIndex);
        String valueString = arg.substring(equalSignIndex + 1);
        OptionValue<?> value = named.get(optionName);
        if (value == null) {
            return false;
        }
        int index = value.parse(new String[]{valueString}, 0);
        return index > 0;
    }

    /**
     * Parses the full command (including subcommands, named and positional options) from command line arguments.
     *
     * @param args   the full array of program arguments as received in {@code main}.
     * @param offset starting index from which arguments in {@code args} should be parsed.
     * @return the index of the first argument in {@code args} that was not consumed during parsing of the command.
     * @throws InvalidArgumentException if an option could not be parsed successfully.
     * @throws MissingArgumentException if a required argument was not present among {@code args}
     * @throws HelpRequestedException   if the --help flag was present among {@code args}.
     */
    public int parse(String[] args, int offset) throws InvalidArgumentException, MissingArgumentException, HelpRequestedException {
        int nextPositionalArg = 0;
        int index = offset;
        while (index < args.length) {
            if (args[index].contentEquals(SEPARATOR)) {
                index++;
                break;
            }
            if (args[index].contentEquals(HELP)) {
                throw new HelpRequestedException(this);
            }
            if (commandGroup != null && commandGroup.getSelectedCommand() == null) {
                index = commandGroup.parse(args, index);
                continue;
            }
            // Split up argument of the form option=value
            int equalSignIndex = args[index].indexOf(EQUAL_SIGN);
            if (equalSignIndex > -1 && parseEqualsValue(args[index], equalSignIndex)) {
                index++;
                continue;
            }
            OptionValue<?> value = named.get(args[index]);
            if (value == null) {
                if (nextPositionalArg >= positional.size()) {
                    break;
                }
                value = positional.get(nextPositionalArg++);
            }
            index = value.parse(args, index);
        }
        if (commandGroup != null && commandGroup.getSelectedCommand() == null) {
            throw new MissingArgumentException("SUBCOMMAND");
        }
        var cursor = named.getEntries();
        while (cursor.advance()) {
            String name = cursor.getKey();
            OptionValue<?> value = cursor.getValue();
            if (!value.isSet() && value.isRequired()) {
                throw new MissingArgumentException(name);
            }
        }
        if (nextPositionalArg < positional.size()) {
            throw new MissingArgumentException(positional.get(nextPositionalArg).getName());
        }
        return index;
    }

    static final String INDENT = "  ";

    static final String HELP_ITEM_FMT = "  %s%n    %s%n";

    public void printUsage(PrintWriter writer) {
        writer.append(getName());
        if (commandGroup != null) {
            writer.append(' ');
            commandGroup.printUsage(writer);
            if (!named.isEmpty() || !positional.isEmpty()) {
                writer.append(" --");
            }
        }
        for (OptionValue<?> option : positional) {
            writer.append(' ');
            writer.append(option.getUsage());
        }
        if (!named.isEmpty()) {
            writer.append(" [OPTIONS]");
        }
    }

    public void printHelp(PrintWriter writer) {
        if (commandGroup != null) {
            commandGroup.printHelp(writer);
            return;
        }

        boolean separate = false;
        if (!positional.isEmpty()) {
            writer.println("ARGS:");
            for (OptionValue<?> arg : positional) {
                if (separate) {
                    writer.println();
                }
                writer.format(HELP_ITEM_FMT, arg.getUsage(), arg.getDescription());
                separate = true;
            }
        }
        if (!named.isEmpty()) {
            if (separate) {
                writer.println();
                separate = false;
            }
            writer.println("OPTIONS:");
            var cursor = named.getEntries();
            while (cursor.advance()) {
                if (separate) {
                    writer.println();
                }
                String name = cursor.getKey();
                OptionValue<?> value = cursor.getValue();
                writer.format(HELP_ITEM_FMT, String.format("%s %s", name, value.getUsage()), value.getDescription());
                separate = true;
            }
        }
    }
}
