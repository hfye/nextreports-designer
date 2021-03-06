/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ro.nextreports.designer.ui.sqleditor.syntax;

import java.io.CharArrayReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import javax.swing.text.Segment;
import javax.swing.undo.UndoManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author Decebal Suiu
 */
public class SyntaxDocument extends PlainDocument {

    private static final Log LOG = LogFactory.getLog(SyntaxDocument.class);

    protected Lexer lexer;
    protected List<Token> tokens;
    protected UndoManager undo = new CompoundUndoManager();

    public SyntaxDocument(Lexer lexer) {
        super();
        putProperty(PlainDocument.tabSizeAttribute, 4); // outside ?!
        this.lexer = lexer;
        
        // Listen for undo and redo events
        addUndoableEditListener(new UndoableEditListener() {

            public void undoableEditHappened(UndoableEditEvent event) {
                if (event.getEdit().isSignificant()) {
                    undo.addEdit(event.getEdit());
                }
            }
            
        });
    }

    @Override
    public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
        super.insertString(offs, str, a);
        parse();
    }

    @Override
    public void remove(int offs, int len) throws BadLocationException {
        super.remove(offs, len);
        parse();
    }
    
    @Override
    public void replace(int offset, int length, String text, AttributeSet attrs) 
            throws BadLocationException {
        super.replace(offset, length, text, attrs);
        parse();
    }

    /**
     * Find the token at a given position.  May return null if no token is
     * found (whitespace skipped) or if the position is out of range.
     * 
     * @param pos
     * @return
     */
    public Token getTokenAt(int pos) {
        if (tokens == null || tokens.isEmpty() || pos > getLength()) {
            return null;
        }
        
        Token token = null;
        Token tKey = new Token(TokenType.DEFAULT, pos, 1);
        @SuppressWarnings("unchecked")
        int ndx = Collections.binarySearch((List) tokens, tKey);
        if (ndx < 0) {
            // so, start from one before the token where we should be...
            // -1 to get the location, and another -1 to go back..
            ndx = (-ndx - 1 - 1 < 0) ? 0 : (-ndx - 1 - 1);
            Token t = tokens.get(ndx);
            if ((t.start <= pos) && (pos <= (t.start + t.length))) {
                token = t;
            }
        } else {
            token = tokens.get(ndx);
        }
        
        return token;
    }

    /**
     * Perform an undo action, if possible
     */
    public void doUndo() {
        if (undo.canUndo()) {
            undo.undo();
            parse();
        }
    }

    /**
     * Perform a redo action, if possible.
     */
    public void doRedo() {
        if (undo.canRedo()) {
            undo.redo();
            parse();
        }
    }
    
    /**
     * This will discard all undoable edits
     */
    public void clearUndos() {
        undo.discardAllEdits();
    }

    /**
     * Return an iterator of tokens between p0 and p1.
     * 
     * @param start
     * @param end
     * @return
     */
    protected Iterator<Token> getTokens(int start, int end) {
        return new TokenIterator(start, end);
    }

    /**
     * Parse the entire document and return list of tokens that do not already
     * exist in the tokens list. There may be overlaps, and replacements, 
     * which we will cleanup later.
     * 
     * @return list of tokens that do not exist in the tokens field 
     */
    private void parse() {
        // if we have no lexer, then we must have no tokens...
        if (lexer == null) {
            tokens = null;
            return;
        }
        
        List<Token> tokens = new ArrayList<Token>(getLength() / 10);
        long time = System.nanoTime();
        int length = getLength();
        try {
            Segment segment = new Segment();
            getText(0, getLength(), segment);
            CharArrayReader reader = new CharArrayReader(segment.array, segment.offset, segment.count);
            lexer.yyreset(reader);
            Token token;
            while ((token = lexer.yylex()) != null) {
                tokens.add(token);
            }
        } catch (BadLocationException e) {
            LOG.error(e.getMessage(), e);
        } catch (IOException e) {
            // This will not be thrown from the Lexer
            LOG.error(e.getMessage(), e);
        } finally {
            // Benchmarks:
            // Parsed 574038 chars in 81 ms, giving 74584 tokens
//                System.out.printf("Parsed %d in %d ms, giving %d tokens",
//                        len, (System.nanoTime() - ts) / 1000000, toks.size());
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Parsed %d in %d ms, giving %d tokens",
                        length, (System.nanoTime() - time) / 1000000, tokens.size()));
            }
            this.tokens = tokens;
        }
    }
    
    /**
     * This class is used to iterate over tokens between two positions.
     */
    class TokenIterator implements Iterator<Token> {

        int start;
        int end;
        int ndx = 0;

        @SuppressWarnings("unchecked")
        private TokenIterator(int start, int end) {
            this.start = start;
            this.end = end;
            if (tokens != null && !tokens.isEmpty()) {
                Token token = new Token(TokenType.COMMENT, start, end - start);
                ndx = Collections.binarySearch((List) tokens, token);
                // we will probably not find the exact token...
                if (ndx < 0) {
                    // so, start from one before the token where we should be...
                    // -1 to get the location, and another -1 to go back..
                    ndx = (-ndx - 1 - 1 < 0) ? 0 : (-ndx - 1 - 1);
                    Token t = tokens.get(ndx);
                    // if the prev token does not overlap, then advance one
                    if (t.start + t.length <= start) {
                        ndx++;
                    }

                }
            }
        }

        public boolean hasNext() {
            if (tokens == null) {
                return false;
            }
            if (ndx >= tokens.size()) {
                return false;
            }
            Token t = tokens.get(ndx);
            if (t.start >= end) {
                return false;
            }
            
            return true;
        }

        public Token next() {
            return tokens.get(ndx++);
        }

        public void remove() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
        
    }

}
