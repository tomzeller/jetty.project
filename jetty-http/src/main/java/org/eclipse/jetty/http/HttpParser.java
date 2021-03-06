//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpParser
{
    public static final Logger LOG = Log.getLogger(HttpParser.class);
    static final int INITIAL_URI_LENGTH=256;

    // States
    public enum State
    {
        START,
        METHOD,
        RESPONSE_VERSION,
        SPACE1,
        STATUS,
        URI,
        SPACE2,
        REQUEST_VERSION,
        REASON,
        HEADER,
        HEADER_NAME,
        HEADER_IN_NAME,
        HEADER_VALUE,
        HEADER_IN_VALUE,
        END,
        EOF_CONTENT,
        CONTENT,
        CHUNKED_CONTENT,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        CLOSED
    };

    private final HttpHandler<ByteBuffer> _handler;
    private final RequestHandler<ByteBuffer> _requestHandler;
    private final ResponseHandler<ByteBuffer> _responseHandler;
    private final int _maxHeaderBytes;
    private HttpField _field;
    private HttpHeader _header;
    private String _headerString;
    private HttpHeaderValue _value;
    private String _valueString;
    private int _responseStatus;
    private int _headerBytes;
    private boolean _host;

    /* ------------------------------------------------------------------------------- */
    private volatile State _state=State.START;
    private HttpMethod _method;
    private String _methodString;
    private HttpVersion _version;
    private ByteBuffer _uri=ByteBuffer.allocate(INITIAL_URI_LENGTH); // Tune?
    private EndOfContent _endOfContent;
    private long _contentLength;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
    private boolean _headResponse;
    private boolean _cr;
    private ByteBuffer _contentChunk;
    private Trie<HttpField> _connectionFields;

    private int _length;
    private final StringBuilder _string=new StringBuilder();

    /* ------------------------------------------------------------------------------- */
    public HttpParser(RequestHandler<ByteBuffer> handler)
    {
        this(handler,-1);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler<ByteBuffer> handler)
    {
        this(handler,-1);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(RequestHandler<ByteBuffer> handler,int maxHeaderBytes)
    {
        _handler=handler;
        _requestHandler=handler;
        _responseHandler=null;
        _maxHeaderBytes=maxHeaderBytes;
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler<ByteBuffer> handler,int maxHeaderBytes)
    {
        _handler=handler;
        _requestHandler=null;
        _responseHandler=handler;
        _maxHeaderBytes=maxHeaderBytes;
    }

    /* ------------------------------------------------------------------------------- */
    public long getContentLength()
    {
        return _contentLength;
    }

    /* ------------------------------------------------------------ */
    public long getContentRead()
    {
        return _contentPosition;
    }

    /* ------------------------------------------------------------ */
    /** Set if a HEAD response is expected
     * @param head
     */
    public void setHeadResponse(boolean head)
    {
        _headResponse=head;
    }

    /* ------------------------------------------------------------------------------- */
    public State getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inContentState()
    {
        return _state.ordinal() > State.END.ordinal();
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state.ordinal() < State.END.ordinal();
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isInContent()
    {
        return _state.ordinal()>State.END.ordinal() && _state.ordinal()<State.CLOSED.ordinal();
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isChunking()
    {
        return _endOfContent==EndOfContent.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public boolean isStart()
    {
        return isState(State.START);
    }

    /* ------------------------------------------------------------ */
    public boolean isClosed()
    {
        return isState(State.CLOSED);
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return isState(State.START)||isState(State.END)||isState(State.CLOSED);
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return isState(State.END)||isState(State.CLOSED);
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(State state)
    {
        return _state == state;
    }

    private static class BadMessage extends Error
    {
        private final int _code;
        private final String _message;

        BadMessage()
        {
            this(400,null);
        }
        
        BadMessage(int code)
        {
            this(code,null);
        }
        
        BadMessage(String message)
        {
            this(400,message);
        }
        
        BadMessage(int code,String message)
        {
            _code=code;
            _message=message;
        }
        
    }
    
    /* ------------------------------------------------------------------------------- */
    private byte next(ByteBuffer buffer) 
    {
        byte ch=buffer.get();

        // If not a special character 
        if (ch>=HttpTokens.SPACE || ch<0)
        {
            if (_cr)
                throw new BadMessage("Bad EOL");
            
            /*
            if (ch>HttpTokens.SPACE)
                System.err.println("Next "+(char)ch);
            else
                System.err.println("Next ["+ch+"]");*/
            return ch;   
        }
            
        
        // Only a LF acceptable after CR
        if (_cr)
        {
            _cr=false;
            if (ch==HttpTokens.LINE_FEED)
                return ch;

            throw new BadMessage("Bad EOL");
        }
        
        // If it is a CR
        if (ch==HttpTokens.CARRIAGE_RETURN)
        {
            // Skip CR and look for a LF
            if (buffer.hasRemaining())
            {
                if(_maxHeaderBytes>0 && _state.ordinal()<State.END.ordinal())
                    _headerBytes++;
                ch=buffer.get();
                if (ch==HttpTokens.LINE_FEED)
                    return ch;

                throw new BadMessage();
            }

            // Defer lookup of LF
            _cr=true;
            return 0;
        }
        
        // Only LF or TAB acceptable special characters
        if (ch!=HttpTokens.LINE_FEED && ch!=HttpTokens.TAB)
            throw new BadMessage();
        
        /*
        if (ch>HttpTokens.SPACE)
            System.err.println("Next "+(char)ch);
        else
            System.err.println("Next ["+ch+"]");
            */
        return ch;
    }
    
    /* ------------------------------------------------------------------------------- */
    /* Quick lookahead for the start state looking for a request method or a HTTP version,
     * otherwise skip white space until something else to parse.
     */
    private boolean quickStart(ByteBuffer buffer)
    {
        // Quick start look
        while (_state==State.START && buffer.hasRemaining())
        {
            if (_requestHandler!=null)
            {
                _method = HttpMethod.lookAheadGet(buffer);
                if (_method!=null)
                {
                    _methodString = _method.asString();
                    buffer.position(buffer.position()+_methodString.length()+1);
                    setState(State.SPACE1);
                    return false;
                }
            }
            else if (_responseHandler!=null)
            {
                _version = HttpVersion.lookAheadGet(buffer);
                if (_version!=null)
                {
                    buffer.position(buffer.position()+_version.asString().length()+1);
                    setState(State.SPACE1);
                    return false;
                }
            }

            byte ch=next(buffer);
            
            if (ch > HttpTokens.SPACE)
            {
                _string.setLength(0);
                _string.append((char)ch);
                setState(_requestHandler!=null?State.METHOD:State.RESPONSE_VERSION);
                return false;
            }
        }
        return false;
    }

    private String takeString()
    {
        String s =_string.toString();
        _string.setLength(0);
        return s;
    }

    private String takeLengthString()
    {
        _string.setLength(_length);
        String s =_string.toString();
        _string.setLength(0);
        _length=-1;
        return s;
    }

    /* ------------------------------------------------------------------------------- */
    /* Parse a request or response line
     */
    private boolean parseLine(ByteBuffer buffer)
    {
        boolean return_from_parse=false;

        // Process headers
        while (_state.ordinal()<State.HEADER.ordinal() && buffer.hasRemaining() && !return_from_parse)
        {
            // process each character
            byte ch=next(buffer);
            if (ch==-1)
                return true;
            if (ch==0)
                continue;

            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
            {
                if (_state==State.URI)
                {
                    LOG.warn("URI is too large >"+_maxHeaderBytes);
                    throw new BadMessage(HttpStatus.REQUEST_URI_TOO_LONG_414);
                }
                else
                {
                    if (_requestHandler!=null)
                        LOG.warn("request is too large >"+_maxHeaderBytes);
                    else
                        LOG.warn("response is too large >"+_maxHeaderBytes);
                    throw new BadMessage(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413);
                }
            }

            switch (_state)
            {
                case METHOD:
                    if (ch == HttpTokens.SPACE)
                    {
                        _methodString=takeString();
                        HttpMethod method=HttpMethod.CACHE.get(_methodString);
                        if (method!=null)
                            _methodString=method.asString();
                        setState(State.SPACE1);
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"No URI");
                    }
                    else
                        _string.append((char)ch);
                    break;

                case RESPONSE_VERSION:
                    if (ch == HttpTokens.SPACE)
                    {
                        String version=takeString();
                        _version=HttpVersion.CACHE.get(version);
                        if (_version==null)
                        {
                            throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Unknown Version");
                        }
                        setState(State.SPACE1);
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"No Status");
                    }
                    else
                        _string.append((char)ch);
                    break;

                case SPACE1:
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        if (_responseHandler!=null)
                        {
                            setState(State.STATUS);
                            _responseStatus=ch-'0';
                        }
                        else
                        {
                            _uri.clear();
                            setState(State.URI);
                            // quick scan for space or EoBuffer
                            if (buffer.hasArray())
                            {
                                byte[] array=buffer.array();
                                int p=buffer.arrayOffset()+buffer.position();
                                int l=buffer.arrayOffset()+buffer.limit();
                                int i=p;
                                while (i<l && array[i]>HttpTokens.SPACE)
                                    i++;

                                int len=i-p;
                                _headerBytes+=len;
                                
                                if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
                                {
                                    LOG.warn("URI is too large >"+_maxHeaderBytes);
                                    throw new BadMessage(HttpStatus.REQUEST_URI_TOO_LONG_414);
                                }
                                if (_uri.remaining()<=len)
                                {
                                    ByteBuffer uri = ByteBuffer.allocate(_uri.capacity()+2*len);
                                    _uri.flip();
                                    uri.put(_uri);
                                    _uri=uri;
                                }
                                _uri.put(array,p-1,len+1);
                                buffer.position(i-buffer.arrayOffset());
                            }
                            else
                                _uri.put(ch);
                        }
                    }
                    else if (ch < HttpTokens.SPACE)
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,_requestHandler!=null?"No URI":"No Status");
                    }
                    break;

                case STATUS:
                    if (ch == HttpTokens.SPACE)
                    {
                        setState(State.SPACE2);
                    }
                    else if (ch>='0' && ch<='9')
                    {
                        _responseStatus=_responseStatus*10+(ch-'0');
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        return_from_parse|=_responseHandler.startResponse(_version, _responseStatus, null);
                        setState(State.HEADER);
                    }
                    else
                    {
                        throw new IllegalStateException();
                    }
                    break;

                case URI:
                    if (ch == HttpTokens.SPACE)
                    {
                        setState(State.SPACE2);
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        // HTTP/0.9
                        _uri.flip();
                        return_from_parse|=_requestHandler.startRequest(_method,_methodString,_uri,null);
                        setState(State.END);
                        BufferUtil.clear(buffer);
                        return_from_parse|=_handler.headerComplete();
                        return_from_parse|=_handler.messageComplete();
                    }
                    else
                    {
                        if (!_uri.hasRemaining())
                        {
                            ByteBuffer uri = ByteBuffer.allocate(_uri.capacity()*2);
                            _uri.flip();
                            uri.put(_uri);
                            _uri=uri;
                        }
                        _uri.put(ch);
                    }
                    break;

                case SPACE2:
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        _string.setLength(0);
                        _string.append((char)ch);
                        if (_responseHandler!=null)
                        {
                            _length=1;
                            setState(State.REASON);
                        }
                        else
                        {
                            setState(State.REQUEST_VERSION);

                            // try quick look ahead for HTTP Version
                            if (buffer.position()>0 && buffer.hasArray())
                            {
                                HttpVersion version=HttpVersion.lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position()-1,buffer.arrayOffset()+buffer.limit());
                                if (version!=null) 
                                {
                                    int pos = buffer.position()+version.asString().length()-1;
                                    if (pos<buffer.limit())
                                    {
                                        byte n=buffer.get(pos);
                                        if (n==HttpTokens.CARRIAGE_RETURN)
                                        {
                                            _cr=true;
                                            _version=version;
                                            _string.setLength(0);
                                            buffer.position(pos+1);
                                        }
                                        else if (n==HttpTokens.LINE_FEED)
                                        {
                                            _version=version;
                                            _string.setLength(0);
                                            buffer.position(pos);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else if (ch == HttpTokens.LINE_FEED)
                    {
                        if (_responseHandler!=null)
                        {
                            return_from_parse|=_responseHandler.startResponse(_version, _responseStatus, null);
                            setState(State.HEADER);
                        }
                        else
                        {
                            // HTTP/0.9
                            _uri.flip();
                            return_from_parse|=_requestHandler.startRequest(_method,_methodString,_uri, null);
                            setState(State.END);
                            BufferUtil.clear(buffer);
                            return_from_parse|=_handler.headerComplete();
                            return_from_parse|=_handler.messageComplete();
                        }
                    }
                    break;

                case REQUEST_VERSION:
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        if (_version==null)
                            _version=HttpVersion.CACHE.get(takeString());
                        if (_version==null)
                        {
                            throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Unknown Version");
                        }
                        
                        // Should we try to cache header fields?
                        if (_connectionFields==null && _version.getVersion()>=HttpVersion.HTTP_1_1.getVersion())
                        {
                            int header_cache = _handler.getHeaderCacheSize();
                            if (header_cache>0)
                                _connectionFields=new ArrayTernaryTrie<>(header_cache);
                        }

                        setState(State.HEADER);
                        _uri.flip();
                        return_from_parse|=_requestHandler.startRequest(_method,_methodString,_uri, _version);
                        continue;
                    }
                    else
                        _string.append((char)ch);

                    break;

                case REASON:
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        String reason=takeLengthString();

                        setState(State.HEADER);
                        return_from_parse|=_responseHandler.startResponse(_version, _responseStatus, reason);
                        continue;
                    }
                    else
                    {
                        _string.append((char)ch);
                        if (ch!=' '&&ch!='\t')
                            _length=_string.length();
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());

            }
        }

        return return_from_parse;
    }

    private boolean handleKnownHeaders(ByteBuffer buffer)
    {
        boolean add_to_connection_trie=false;
        switch (_header)
        {
            case CONTENT_LENGTH:
                if (_endOfContent != EndOfContent.CHUNKED_CONTENT)
                {
                    try
                    {
                        _contentLength=Long.parseLong(_valueString);
                    }
                    catch(NumberFormatException e)
                    {
                        LOG.ignore(e);
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad Content-Length");
                    }
                    if (_contentLength <= 0)
                        _endOfContent=EndOfContent.NO_CONTENT;
                    else
                        _endOfContent=EndOfContent.CONTENT_LENGTH;
                }
                break;

            case TRANSFER_ENCODING:
                if (_value==HttpHeaderValue.CHUNKED)
                    _endOfContent=EndOfContent.CHUNKED_CONTENT;
                else
                {
                    if (_valueString.endsWith(HttpHeaderValue.CHUNKED.toString()))
                        _endOfContent=EndOfContent.CHUNKED_CONTENT;
                    else if (_valueString.indexOf(HttpHeaderValue.CHUNKED.toString()) >= 0)
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad chunking");
                    }
                }
                break;

            case HOST:
                add_to_connection_trie=_connectionFields!=null && _field==null;
                _host=true;
                String host=_valueString;
                int port=0;
                if (host==null || host.length()==0)
                {
                    throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad Host header");
                }

                int len=host.length();
                loop: for (int i = len; i-- > 0;)
                {
                    char c2 = (char)(0xff & host.charAt(i));
                    switch (c2)
                    {
                        case ']':
                            break loop;

                        case ':':
                            try
                            {
                                len=i;
                                port = StringUtil.toInt(host.substring(i+1));
                            }
                            catch (NumberFormatException e)
                            {
                                LOG.debug(e);
                                throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad Host header");
                            }
                            break loop;
                    }
                }
                if (host.charAt(0)=='[')
                {
                    if (host.charAt(len-1)!=']') 
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad IPv6 Host header");
                    }
                    host = host.substring(1,len-1);
                }
                else if (len!=host.length())
                    host = host.substring(0,len);
                
                if (_requestHandler!=null)
                    _requestHandler.parsedHostHeader(host,port);
                
              break;
              
            case CONNECTION:
                // Don't cache if not persistent
                if (_valueString!=null && _valueString.indexOf("close")>=0)
                    _connectionFields=null;
                break;

            case AUTHORIZATION:
            case ACCEPT:
            case ACCEPT_CHARSET:
            case ACCEPT_ENCODING:
            case ACCEPT_LANGUAGE:
            case COOKIE:
            case CACHE_CONTROL:
            case USER_AGENT:
                add_to_connection_trie=_connectionFields!=null && _field==null;
                break;
                
            default: break;
        }
    
        if (add_to_connection_trie && !_connectionFields.isFull() && _header!=null && _valueString!=null)
        {
            _field=new HttpField.CachedHttpField(_header,_valueString);
            _connectionFields.put(_field);
        }
        
        return false;
    }
    
    
    /* ------------------------------------------------------------------------------- */
    /*
     * Parse the message headers and return true if the handler has signaled for a return
     */
    private boolean parseHeaders(ByteBuffer buffer)
    {
        boolean return_from_parse=false;

        // Process headers
        while (_state.ordinal()<State.END.ordinal() && buffer.hasRemaining() && !return_from_parse)
        {
            // process each character
            byte ch=next(buffer);
            if (ch==-1)
                return true;
            if (ch==0)
                continue;
            
            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
            {
                LOG.warn("Header is too large >"+_maxHeaderBytes);
                throw new BadMessage(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413);
            }

            switch (_state)
            {
                case HEADER:
                    switch(ch)
                    {
                        case HttpTokens.COLON:
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                        {
                            // header value without name - continuation?
                            _string.setLength(0);
                            if (_valueString!=null)
                            {
                                _string.append(_valueString);
                                _string.append(' ');
                            }
                            _length=_string.length();
                            _valueString=null;
                            setState(State.HEADER_VALUE);
                            break;
                        }

                        default:
                        {
                            // handler last header if any.  Delayed to here just in case there was a continuation line (above)
                            if (_headerString!=null || _valueString!=null)
                            {
                                // Handle known headers
                                if (_header!=null && handleKnownHeaders(buffer))
                                {
                                    _headerString=_valueString=null;
                                    _header=null;
                                    _value=null;
                                    _field=null;
                                    return true;
                                }
                                return_from_parse|=_handler.parsedHeader(_field!=null?_field:new HttpField(_header,_headerString,_valueString));
                            }
                            _headerString=_valueString=null;
                            _header=null;
                            _value=null;
                            _field=null;

                            // now handle the ch
                            if (ch == HttpTokens.LINE_FEED)
                            {
                                _contentPosition=0;

                                // End of headers!

                                // Was there a required host header?
                                if (!_host && _version!=HttpVersion.HTTP_1_0 && _requestHandler!=null)
                                {
                                    throw new BadMessage(HttpStatus.BAD_REQUEST_400,"No Host");
                                }

                                // is it a response that cannot have a body?
                                if (_responseHandler !=null  && // response  
                                    (_responseStatus == 304  || // not-modified response
                                    _responseStatus == 204 || // no-content response
                                    _responseStatus < 200)) // 1xx response
                                    _endOfContent=EndOfContent.NO_CONTENT; // ignore any other headers set
                                
                                // else if we don't know framing
                                else if (_endOfContent == EndOfContent.UNKNOWN_CONTENT)
                                {
                                    if (_responseStatus == 0  // request
                                            || _responseStatus == 304 // not-modified response
                                            || _responseStatus == 204 // no-content response
                                            || _responseStatus < 200) // 1xx response
                                        _endOfContent=EndOfContent.NO_CONTENT;
                                    else
                                        _endOfContent=EndOfContent.EOF_CONTENT;
                                }

                                // How is the message ended?
                                switch (_endOfContent)
                                {
                                    case EOF_CONTENT:
                                        setState(State.EOF_CONTENT);
                                        return_from_parse|=_handler.headerComplete();
                                        break;

                                    case CHUNKED_CONTENT:
                                        setState(State.CHUNKED_CONTENT);
                                        return_from_parse|=_handler.headerComplete();
                                        break;

                                    case NO_CONTENT:
                                        return_from_parse|=_handler.headerComplete();
                                        setState(State.END);
                                        return_from_parse|=_handler.messageComplete();
                                        break;

                                    default:
                                        setState(State.CONTENT);
                                        return_from_parse|=_handler.headerComplete();
                                        break;
                                }
                            }
                            else
                            {
                                if (buffer.hasRemaining())
                                {
                                    // Try a look ahead for the known header name and value.
                                    HttpField field=_connectionFields==null?null:_connectionFields.getBest(buffer,-1,buffer.remaining());
                                    if (field==null)
                                        field=HttpField.CACHE.getBest(buffer,-1,buffer.remaining());
                                        
                                    if (field!=null)
                                    {
                                        String n=field.getName();
                                        String v=field.getValue();
         
                                        if (v==null)
                                        {
                                            // Header only
                                            _header=field.getHeader();
                                            _headerString=n;
                                            setState(State.HEADER_VALUE);
                                            _string.setLength(0);
                                            _length=0;
                                            buffer.position(buffer.position()+n.length()+1);
                                            break;
                                        }
                                        else
                                        {
                                            // Header and value
                                            int pos=buffer.position()+n.length()+v.length()+1;
                                            byte b=buffer.get(pos);

                                            if (b==HttpTokens.CARRIAGE_RETURN || b==HttpTokens.LINE_FEED)
                                            {                     
                                                _field=field;
                                                _header=_field.getHeader();
                                                _headerString=n;
                                                _valueString=v;
                                                setState(State.HEADER_IN_VALUE);

                                                if (b==HttpTokens.CARRIAGE_RETURN)
                                                {
                                                    _cr=true;
                                                    buffer.position(pos+1);
                                                }
                                                else
                                                    buffer.position(pos);
                                                break;
                                            }
                                        }
                                    }
                                }

                                // New header
                                setState(State.HEADER_NAME);
                                _string.setLength(0);
                                _string.append((char)ch);
                                _length=1;
                            }
                        }
                    }

                    break;

                case HEADER_NAME:
                    switch(ch)
                    {
                        case HttpTokens.LINE_FEED:
                            if (_headerString==null)
                            {
                                _headerString=takeLengthString();
                                _header=HttpHeader.CACHE.get(_headerString);
                            }
                            setState(State.HEADER);

                            break;

                        case HttpTokens.COLON:
                            if (_headerString==null)
                            {
                                _headerString=takeLengthString();
                                _header=HttpHeader.CACHE.get(_headerString); 
                            }
                            setState(State.HEADER_VALUE);
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            _string.append((char)ch);
                            break;
                        default:
                        {
                            _string.append((char)ch);
                            _length=_string.length();
                            setState(State.HEADER_IN_NAME);
                        }
                    }

                    break;

                case HEADER_IN_NAME:
                    switch(ch)
                    {
                        case HttpTokens.LINE_FEED:
                            _headerString=takeString();
                            _length=-1;
                            _header=HttpHeader.CACHE.get(_headerString);
                            setState(State.HEADER);
                            break;

                        case HttpTokens.COLON:
                            if (_headerString==null)
                            {
                                _headerString=takeString();
                                _header=HttpHeader.CACHE.get(_headerString);
                            }
                            _length=-1;
                            setState(State.HEADER_VALUE);
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            if (_header!=null)
                            {
                                _string.setLength(0);
                                _string.append(_header.asString());
                                _length=_string.length();
                                _header=null;
                                _headerString=null;
                            }
                            setState(State.HEADER_NAME);
                            _string.append((char)ch);
                            break;
                        default:
                            if (_header!=null)
                            {
                                _string.setLength(0);
                                _string.append(_header.asString());
                                _length=_string.length();
                                _header=null;
                                _headerString=null;
                            }
                            _string.append((char)ch);
                            _length++;
                    }
                    break;

                case HEADER_VALUE:
                    switch(ch)
                    {
                        case HttpTokens.LINE_FEED:
                            if (_length > 0)
                            {
                                if (_valueString!=null)
                                {
                                    // multi line value!
                                    _value=null;
                                    _valueString+=" "+takeLengthString();
                                }
                                else if (HttpHeaderValue.hasKnownValues(_header))
                                {
                                    _valueString=takeLengthString();
                                    _value=HttpHeaderValue.CACHE.get(_valueString);
                                }
                                else
                                {
                                    _value=null;
                                    _valueString=takeLengthString();
                                }
                            }
                            setState(State.HEADER);
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            break;
                        default:
                        {
                            _string.append((char)ch);
                            _length=_string.length();
                            setState(State.HEADER_IN_VALUE);
                        }
                    }
                    break;

                case HEADER_IN_VALUE:
                    switch(ch)
                    {
                        case HttpTokens.LINE_FEED:
                            if (_length > 0)
                            {
                                if (HttpHeaderValue.hasKnownValues(_header))
                                {
                                    _valueString=takeString();
                                    _value=HttpHeaderValue.CACHE.get(_valueString);
                                }
                                else
                                {
                                    _value=null;
                                    _valueString=takeString();
                                }
                                _length=-1;
                            }
                            setState(State.HEADER);
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            if (_valueString!=null)
                            {
                                _string.setLength(0);
                                _string.append(_valueString);
                                _length=_valueString.length();
                                _valueString=null;
                                _field=null;
                            }
                            _string.append((char)ch);
                            setState(State.HEADER_VALUE);
                            break;
                        default:
                            if (_valueString!=null)
                            {
                                _string.setLength(0);
                                _string.append(_valueString);
                                _length=_valueString.length();
                                _valueString=null;
                                _field=null;
                            }
                            _string.append((char)ch);
                            _length++;
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());

            }
        }

        return return_from_parse;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parseNext(ByteBuffer buffer)
    {
        try
        {
            // handle initial state
            switch(_state)
            {
                case START:
                    _version=null;
                    _method=null;
                    _methodString=null;
                    _endOfContent=EndOfContent.UNKNOWN_CONTENT;
                    _header=null;
                    if(quickStart(buffer))
                        return true;
                    break;

                case CONTENT:
                    if (_contentPosition==_contentLength)
                    {
                        setState(State.END);
                        if(_handler.messageComplete())
                            return true;
                    }
                    break;

                case END:
                    // eat white space
                    while (buffer.remaining()>0 && buffer.get(buffer.position())<=HttpTokens.SPACE)
                        buffer.get();
                    return false;

                case CLOSED:
                    if (BufferUtil.hasContent(buffer))
                    {
                        // Just ignore data when closed
                        _headerBytes+=buffer.remaining();
                        BufferUtil.clear(buffer);
                        if (_headerBytes>_maxHeaderBytes)
                        {
                            // Don't want to waste time reading data of a closed request
                            throw new IllegalStateException("too much data after closed");
                        }
                    }
                    return false;
                default: break;
    
            }

            // Request/response line
            if (_state.ordinal()<State.HEADER.ordinal())
                if (parseLine(buffer))
                    return true;

            if (_state.ordinal()<State.END.ordinal())
                if (parseHeaders(buffer))
                    return true;

            // Handle HEAD response
            if (_responseStatus>0 && _headResponse)
            {
                setState(State.END);
                if (_handler.messageComplete())
                    return true;
            }


            // Handle _content
            byte ch;
            while (_state.ordinal() > State.END.ordinal() && buffer.hasRemaining())
            {
                switch (_state)
                {
                    case EOF_CONTENT:
                        _contentChunk=buffer.asReadOnlyBuffer();
                        _contentPosition += _contentChunk.remaining();
                        buffer.position(buffer.position()+_contentChunk.remaining());
                        if (_handler.content(_contentChunk))
                            return true;
                        break;

                    case CONTENT:
                    {
                        long remaining=_contentLength - _contentPosition;
                        if (remaining == 0)
                        {
                            setState(State.END);
                            if (_handler.messageComplete())
                                return true;
                        }
                        else
                        {
                            _contentChunk=buffer.asReadOnlyBuffer();

                            // limit content by expected size
                            if (_contentChunk.remaining() > remaining)
                            {
                                // We can cast remaining to an int as we know that it is smaller than
                                // or equal to length which is already an int.
                                _contentChunk.limit(_contentChunk.position()+(int)remaining);
                            }

                            _contentPosition += _contentChunk.remaining();
                            buffer.position(buffer.position()+_contentChunk.remaining());

                            if (_handler.content(_contentChunk))
                                return true;

                            if(_contentPosition == _contentLength)
                            {
                                setState(State.END);
                                if (_handler.messageComplete())
                                    return true;
                            }
                        }
                        break;
                    }

                    case CHUNKED_CONTENT:
                    {
                        ch=next(buffer);
                        if (ch>HttpTokens.SPACE)
                        {
                            _chunkLength=TypeUtil.convertHexDigit(ch);
                            _chunkPosition=0;
                            setState(State.CHUNK_SIZE);
                        }
                        
                        break;
                    }

                    case CHUNK_SIZE:
                    {
                        ch=next(buffer);
                        if (ch == HttpTokens.LINE_FEED)
                        {
                            if (_chunkLength == 0)
                            {
                                setState(State.END);
                                if (_handler.messageComplete())
                                    return true;
                            }
                            else
                                setState(State.CHUNK);
                        }
                        else if (ch <= HttpTokens.SPACE || ch == HttpTokens.SEMI_COLON)
                            setState(State.CHUNK_PARAMS);
                        else 
                            _chunkLength=_chunkLength * 16 + TypeUtil.convertHexDigit(ch);
                        break;
                    }

                    case CHUNK_PARAMS:
                    {
                        ch=next(buffer);
                        if (ch == HttpTokens.LINE_FEED)
                        {
                            if (_chunkLength == 0)
                            {
                                setState(State.END);
                                if (_handler.messageComplete())
                                    return true;
                            }
                            else
                                setState(State.CHUNK);
                        }
                        break;
                    }

                    case CHUNK:
                    {
                        int remaining=_chunkLength - _chunkPosition;
                        if (remaining == 0)
                        {
                            setState(State.CHUNKED_CONTENT);
                        }
                        else
                        {
                            _contentChunk=buffer.asReadOnlyBuffer();

                            if (_contentChunk.remaining() > remaining)
                                _contentChunk.limit(_contentChunk.position()+remaining);
                            remaining=_contentChunk.remaining();

                            _contentPosition += remaining;
                            _chunkPosition += remaining;
                            buffer.position(buffer.position()+remaining);
                            if (_handler.content(_contentChunk))
                                return true;
                        }
                        break;
                    }
                    case CLOSED:
                    {
                        BufferUtil.clear(buffer);
                        return false;
                    }
                    
                    default: 
                        break;
                }
            }

            return false;
        }
        catch(BadMessage e)
        {
            BufferUtil.clear(buffer);

            LOG.warn("badMessage: "+e._code+(e._message!=null?" "+e._message:"")+" for "+_handler);
            LOG.debug(e);
            setState(State.CLOSED);
            _handler.badMessage(e._code, e._message);
            return false;
        }
        catch(Exception e)
        {
            BufferUtil.clear(buffer);

            LOG.warn("badMessage: "+e.toString()+" for "+_handler);
            LOG.debug(e);
            
            if (_state.ordinal()<=State.END.ordinal())
            {
                setState(State.CLOSED);
                _handler.badMessage(400,null);
            }
            else
            {
                _handler.earlyEOF();
                setState(State.CLOSED);
            }

            return false;
        }
    }

    /**
     * Notifies this parser that I/O code read a -1 and therefore no more data will arrive to be parsed.
     * Calling this method may result in an invocation to {@link HttpHandler#messageComplete()}, for
     * example when the content is delimited by the close of the connection.
     * If the parser is already in a state that does not need data (for example, it is idle waiting for
     * a request/response to be parsed), then calling this method is a no-operation.
     *
     * @return the result of the invocation to {@link HttpHandler#messageComplete()} if there has been
     * one, or false otherwise.
     */
    public boolean shutdownInput()
    {
        LOG.debug("shutdownInput {}", this);

        // was this unexpected?
        switch(_state)
        {
            case START:
            case END:
                break;

            case EOF_CONTENT:
                setState(State.END);
                return _handler.messageComplete();

            case CLOSED:
                break;

            default:
                setState(State.END);
                if (!_headResponse)
                    _handler.earlyEOF();
                return _handler.messageComplete();
        }

        return false;
    }

    /* ------------------------------------------------------------------------------- */
    public void close()
    {
        switch(_state)
        {
            case START:
            case CLOSED:
            case END:
                break;
                
            case EOF_CONTENT:
                _handler.messageComplete();
                break;
                
            default:
                if (_state.ordinal()>State.END.ordinal())
                {
                    _handler.earlyEOF();
                    _handler.messageComplete();
                }
                else
                    LOG.warn("Closing {}",this);
        }
        setState(State.CLOSED);
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
        _contentLength=-1;
        _contentPosition=0;
        _responseStatus=0;
        _headerBytes=0;
        _contentChunk=null;
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        // reset state
        setState(State.START);
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
        _contentLength=-1;
        _contentPosition=0;
        _responseStatus=0;
        _contentChunk=null;
        _headerBytes=0;
        _host=false;
    }

    /* ------------------------------------------------------------------------------- */
    private void setState(State state)
    {
        // LOG.debug("{} --> {}",_state,state);
        _state=state;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%s,%d of %d}",
                getClass().getSimpleName(),
                _state,
                _contentPosition,
                _contentLength);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* Event Handler interface
     * These methods return true if they want parsing to return to
     * the caller.
     */
    public interface HttpHandler<T>
    {
        public boolean content(T item);

        public boolean headerComplete();

        public boolean messageComplete();

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         * @param field The field parsed
         * @return True if the parser should return to its caller
         */
        public boolean parsedHeader(HttpField field);

        /* ------------------------------------------------------------ */
        /** Called to signal that an EOF was received unexpectedly
         * during the parsing of a HTTP message
         * @return True if the parser should return to its caller
         */
        public void earlyEOF();

        /* ------------------------------------------------------------ */
        /** Called to signal that a bad HTTP message has been received.
         * @param status The bad status to send
         * @param reason The textual reason for badness
         */
        public void badMessage(int status, String reason);
        
        /* ------------------------------------------------------------ */
        /** @return the size in bytes of the per parser header cache
         */
        public int getHeaderCacheSize();
    }

    public interface RequestHandler<T> extends HttpHandler<T>
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         * @param method The method as enum if of a known type
         * @param methodString The method as a string
         * @param uri The raw bytes of the URI.  These are copied into a ByteBuffer that will not be changed until this parser is reset and reused.
         * @param version
         * @return true if handling parsing should return.
         */
        public abstract boolean startRequest(HttpMethod method, String methodString, ByteBuffer uri, HttpVersion version);

        /**
         * This is the method called by the parser after it has parsed the host header (and checked it's format). This is
         * called after the {@link HttpHandler#parsedHeader(HttpField) methods and before
         * HttpHandler#headerComplete();
         */
        public abstract boolean parsedHostHeader(String host,int port);
    }

    public interface ResponseHandler<T> extends HttpHandler<T>
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startResponse(HttpVersion version, int status, String reason);
    }

    public Trie<HttpField> getFieldCache()
    {
        return _connectionFields;
    }

}
