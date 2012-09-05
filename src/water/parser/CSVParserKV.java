package water.parser;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

import water.UKV;
import water.Key;
import water.Value;
import water.ValueArray;

/**
 * Simplified version of CSVParser. Parses either stream of arraylets or just a
 * byte array.
 */
public class CSVParserKV<T> implements Iterable<T>, Iterator<T> {

  public static final int FILL_PARTIAL_RECORDS_WITH_DEFAULTS = 100;
  public static final int DROP_PARTIAL_RECORDS = 101;
  public static final int ERROR_ON_PARTIAL_RECORDS = 102;

  enum DataType {
    typeNull, typeInt, typeFloat, typeDouble, typeString
  };

  public final class CSVString implements CharSequence, Comparable<String> {
    long _offset;
    int _length;

    public CSVString() {
    }

    public CSVString(long offset, int length) {
      _offset = offset;
      _length = length;
    }

    private int[] chunkIdxAndOffset() {
      int[] res = new int[2];
      res[1] = (int) (_offset & ((1 << ValueArray.LOG_CHK) - 1));
      res[0] = (int) (_offset >> ValueArray.LOG_CHK);
      if ((res[0] == _nextChunkIdx)
          && (_data.length > (1 << ValueArray.LOG_CHK))) {
        res[0] -= 1;
        res[1] += 1 << ValueArray.LOG_CHK;
      }
      return res;
    }

    private byte[] data(int chunkIdx, int len) {
      int currentChunkIdx = (int) (_currentOffset >> ValueArray.LOG_CHK);
      if (chunkIdx == currentChunkIdx) {
        return _data;
      }
      if (chunkIdx == _nextChunkIdx)
        return _nextData;
      // we do not have the chunk loaded anymore...find it and load it
      Key k = ValueArray.getChunk(_key, chunkIdx);
      Value v = UKV.get(k);
      return v.get(len);
    }

    public String toString() {
      int[] arr = chunkIdxAndOffset();
      int chunkIdx = arr[0];
      int chunkOffset = arr[1];
      byte[] res = data(chunkIdx, chunkOffset + _length);
      if (res == null)
        System.out.println("*");
      if (res.length >= (chunkOffset + _length)) {
        return new String(res, chunkOffset, _length);
      } else { // we need another chunk
        int len = chunkOffset + _length - res.length;
        byte[] res2 = data(chunkIdx + 1, len);
        return new String(res, chunkOffset, res.length - chunkOffset)
            + new String(res2, 0, len);
      }
    }

    public char charAt(int i) {
      int[] arr = chunkIdxAndOffset();
      int chunkIdx = arr[0];
      int chunkOffset = arr[1] + i;
      byte[] d = data(chunkIdx, chunkOffset + 1);
      if (chunkOffset < d.length)
        return (char) d[chunkOffset];
      chunkOffset -= d.length;
      d = data(chunkIdx + 1, chunkOffset + 1);
      return (char) d[chunkOffset];
    }

    int columns() {
      return _column;
    }

    public int length() {
      return _length;
    }

    public CharSequence subSequence(int start, int end) {
      return new CSVString(_offset + start, end - start);
    }

    public int compareTo(byte[] bytes) {
      int[] arr = chunkIdxAndOffset();
      int chunkIdx = arr[0];
      int chunkOffset = arr[1];
      byte[] data = data(chunkIdx, chunkOffset + _length);

      int mylen = Math.min(_length, data.length - chunkOffset);
      int N = Math.min(mylen, bytes.length);
      int res = 0;
      int i = 0;
      for (; i < N; ++i) {
        if ((res = (data[i + chunkOffset] - bytes[i])) != 0)
          return res;
      }
      if (mylen == _length) { // do we cross boundary?
        return _length - bytes.length;
      }
      int firstChunkLen = data.length;
      // compare the rest of the string
      mylen = _length - mylen;
      data = data(chunkIdx + 1, mylen);
      N = Math.min(mylen, bytes.length);
      for (; i < N; ++i) {
        if ((res = (data[i + chunkOffset - firstChunkLen] - bytes[i])) != 0)
          return res;
      }
      return _length - bytes.length;
    }

    public int compareTo(String o) {
      return compareTo(o.getBytes());
    }

    public boolean equals(String s) {
      return (_length == s.length()) && (compareTo(s) == 0);
    }
  }

  public static class ParserSetup {
    public boolean whiteSpaceSeparator = true;
    public boolean collapseWhiteSpaceSeparators = true;
    public int partialRecordPolicy = DROP_PARTIAL_RECORDS;
    public char separator = ',';
    public int defaultInt = Integer.MAX_VALUE;
    public float defaultFloat = Float.NaN;
    public double defaultDouble = Double.NaN;
  }

  public final ParserSetup _setup;

  // input values variables
  final Key _key; // key to start parsing from
  final int _minChunkIdx; // idx of the first chunk
  final int _maxChunkIdx; // idx of the last chunk + 1 (the first record will
                          // still be parsed if chunk exists

  T _csvRecord;

  CSVString[] _strFields;

  boolean _next;
  int _maxColumn; //max encountered column, if it si higher than our number of columns we skipped some data

  DataType[] _columnTypes;
  boolean _fresh = true;
  byte[] _data;
  byte[] _nextData;
  int _dataPtr;
  int _stringIdx; 
  
  // parsing state
  boolean _skipRecord;
  int _fieldStart;
  int _fieldEnd;
  int _recordStart;
  int _state;
  int _column;

  int _nextChunkIdx;
  long _currentOffset;
  long _nextOffset;
  int _length;

  String[] _columnNames;

  FloatingDecimalWrapper _floatingDecimal = new FloatingDecimalWrapper();

  int _ival;

  public String[] columnNames() {
    return _columnNames;
  }

  @SuppressWarnings("unchecked")
  void initialize() {
    _columnTypes = new DataType[1];
    if (_csvRecord.getClass().getComponentType().equals(Integer.TYPE)) {
      _columnTypes[0] = DataType.typeInt;
    } else if (_csvRecord.getClass().getComponentType().equals(Double.TYPE)) {
      _columnTypes[0] = DataType.typeDouble;
    } else if (_csvRecord.getClass().getComponentType().equals(Float.TYPE)) {
      _columnTypes[0] = DataType.typeFloat;
    } else if (CSVString.class.equals(_csvRecord.getClass()
        .getComponentType())) {
      _columnTypes[0] = DataType.typeString;
      _strFields = new CSVParserKV.CSVString[Array.getLength(_csvRecord)];
      for (int i = 0; i < _strFields.length; ++i) {
        _strFields[i] = new CSVString();
        Array.set(_csvRecord, i, _strFields[i]);
      }
    } else {
      throw new UnsupportedOperationException();
    }
    _skipRecord = (_nextChunkIdx > 1);
  }

  public CSVParserKV(Key k, T csvRecord, ParserSetup setup) {
    // first set the data.
    // If this is a Key for a ValueArray, we will be returned chunk0.
    Value v = UKV.get(k, (int) ValueArray.chunk_size());
    if (v != null) {
      _key = v._key;
      _data = v.get();
      _length = _data.length;
      if (_key._kb[0] == Key.ARRAYLET_CHUNK) {
        _currentOffset = ValueArray.getOffset(_key);
        _minChunkIdx = ValueArray.getChunkIndex(_key);
        _maxChunkIdx = _minChunkIdx + 1;
        _nextChunkIdx = _minChunkIdx + 1;
        Key nextChunk = ValueArray.getChunk(_key, _nextChunkIdx);
        v = UKV.get(nextChunk);
        if (v != null) {
          _nextData = (_nextChunkIdx < _maxChunkIdx) ? v.get() : v.get(1024);
          _length += _nextData.length;
        }
      } else {
        _minChunkIdx = 0;
        _maxChunkIdx = 0;
        _nextChunkIdx = 0;
      }
    } else {
      _minChunkIdx = 0;
      _maxChunkIdx = 0;
      _key = null;
    }
    _setup = setup;
    _csvRecord = csvRecord;
    initialize();
  }

  private final int getDigit(char c) {
    int i = Integer.MAX_VALUE;
    if (Character.isLetterOrDigit(c)) {
      if (Character.isDigit(c)) {
        i = Character.getNumericValue(c);
      } else {
        i = Character.isUpperCase(c) ? (10 + c - 'A') : (10 + c - 'a');
      }
    }
    return i;
  }

  protected boolean parseInt(int from, int to, int radix) {
    int sign = 1;
    int res = 0;
    int state = 0;

    for (int i = from; i < to; ++i) {
      byte b = (i < _data.length) ? _data[i] : _nextData[i - _data.length];
      char ch = (char) b;
      switch (state) {
      case 0:
        if (Character.isWhitespace(ch))
          break;
        else if (ch == '-') {
          sign *= -1;
          break;
        }
        state = 1;
      case 1:
        if (Character.isWhitespace(ch))
          state = 2;
        else {
          int d = getDigit(ch);
          if (d >= radix)
            return false;
          res = radix * res + getDigit(ch);
        }
        break;
      case 2:
        if (!Character.isWhitespace(ch))
          return false;
      }
    }
    _ival = sign * res;
    return true;
  }

  protected boolean parseFloat(int from, int to) {
    int state = 0;
    _floatingDecimal.isNegative = false;
    _floatingDecimal.nDigits = 0;
    _floatingDecimal.decExponent = 0;
    int zeroCounter = 0;
    for (int i = from; i < to; ++i) {
      byte b = (i < _data.length) ? _data[i] : _nextData[i - _data.length];
      char ch = (char) b;
      switch (state) {
      case 0:
        switch (ch) {
        case '.':
          state = 3;
          break;
        case '-':
          _floatingDecimal.isNegative = !_floatingDecimal.isNegative;
          break;
        case '+':
          break;
        case '0':
          _floatingDecimal.digits[_floatingDecimal.nDigits++] = ch;
          ++_floatingDecimal.decExponent;
          state = 1;
          break;
        default:
          if (Character.isWhitespace(ch))
            continue; // trim the leading spaces
          if (!Character.isDigit(ch)) {
            return false;
          }
          state = 2;
          _floatingDecimal.digits[_floatingDecimal.nDigits++] = ch;
          ++_floatingDecimal.decExponent;
        }
        break;
      case 1: // leading zeros
        if (ch == '0')
          break; // ignore leading zeros
        // otherwise fall through
      case 2: // integer part
        switch (ch) {
        case '.':
          state = 3;
          break;
        case 'e':
        case 'E':
          state = 4;
          break;
        case ' ':
        case '\t':
          state = 5; // trim the trailing spaces
          break;
        default:
          if (!Character.isDigit(ch)) {
            return false;
          }
          // too many digits
          if (_floatingDecimal.nDigits == _floatingDecimal.digits.length) {
            return false;
          }
          _floatingDecimal.digits[_floatingDecimal.nDigits++] = ch;
          ++_floatingDecimal.decExponent;
        }
        break;
      case 3: // after decimal point
        switch (ch) {

        case '0':
          ++zeroCounter;
          break;
        case 'e':
        case 'E':
          state = 4;
          break;
        default:
          if (Character.isWhitespace(ch)) {
            state = 5;
            break;
          }
          if (!Character.isDigit(ch)) {
            return false;
          }
          while (zeroCounter > 0) {
            _floatingDecimal.digits[_floatingDecimal.nDigits++] = '0';
            --zeroCounter;
          }
          if (_floatingDecimal.nDigits == _floatingDecimal.digits.length) {
            return false;
          }
          _floatingDecimal.digits[_floatingDecimal.nDigits++] = ch;
        }
        break;
      case 4: // parse int and add it to the exponent
        if (parseInt(i, to, 10))
          _floatingDecimal.decExponent += _ival;
        return (_floatingDecimal.nDigits > 0);
      case 5: // should be trailing spaces...ignore them but throw exception
              // if anything else
        switch (ch) {
        case ' ':
        case '\t':
          break;
        default:
          return false;
        }
        break;
      }
    }
    return (_floatingDecimal.nDigits > 0);
  }

  protected final int ncols() {
    return Array.getLength(_csvRecord);
  }

  protected final DataType columnType() {
    return _columnTypes[0];
  }

  protected boolean endRecord() throws IllegalArgumentException,
      IllegalAccessException {
    boolean res = !_skipRecord;
    int n = ncols();
    if (res && (_column < n)) {
      switch (_setup.partialRecordPolicy) {
      case FILL_PARTIAL_RECORDS_WITH_DEFAULTS:
        int col = _column;
        _fieldStart = _dataPtr;
        _fieldEnd = _dataPtr - 1;
        // fill in the default values for the remaining columns
        for (int i = _column; i < n; ++i) {
          endField();
        }
        _column = col; // preserve the number of columns we actually did parse
        break;
      case DROP_PARTIAL_RECORDS:
        res = false;
        break;
      case ERROR_ON_PARTIAL_RECORDS:
        throw new Error("partial record with " + _column + " columns");
      default:
        throw new Error("illegal _partialRecordPolicy value");
      }
    }
    _recordStart = _dataPtr + 1;
    _fieldStart = _dataPtr + 1;
    _skipRecord = false;
    _stringIdx = 0;
    if (!res) // if we ignore this record, we have to reset the column,
              // otherwise next record would come out as all defaults
      _column = 0; // however, if res is true, we want to keep current column
                   // number so that we know how many columns did we parse
    return res;
  }

  protected void endField() throws IllegalArgumentException,
      IllegalAccessException {
    int n = ncols();
    if (!_skipRecord && (_column < n)) {
      switch (columnType()) {
      case typeInt:
        int ival = (parseInt(_fieldStart, _fieldEnd, 10)) ? _ival : _setup.defaultInt;
        Array.setInt(_csvRecord, _column, ival);
        break;
      case typeFloat:
        float fval = (parseFloat(_fieldStart, _fieldEnd)) ? _floatingDecimal.floatValue() : _setup.defaultFloat;
        Array.setFloat(_csvRecord, _column, fval);
        break;
      case typeDouble:
        double dval = (parseFloat(_fieldStart, _fieldEnd)) ? _floatingDecimal.doubleValue() : _setup.defaultDouble;
        Array.setDouble(_csvRecord, _column, dval);
        break;
      case typeString: {        
        _strFields[_stringIdx]._offset = _currentOffset + _fieldStart;
        _strFields[_stringIdx++]._length = _fieldEnd - _fieldStart;
      }
        break;
      case typeNull:
        break;
      }
    } else if(!_skipRecord && _column >= n && _column > _maxColumn){
      _maxColumn = _column;
    }
    ++_column;
    _fieldStart = _dataPtr + 1;
    _state = STATE_INITIAL;
  }

  private static final int STATE_INITIAL = 104;
  private static final int STATE_QUOTED = 105;
  private static final int STATE_ENDQUOTE = 106;
  private static final int STATE_AFTER_QUOTED = 107;
  private static final int STATE_NONQUOTEDFIELD = 108;
  private static final int STATE_ENDLINE = 109;

  private boolean nextData() {
    if (_nextData == null || _nextChunkIdx == _maxChunkIdx)
      return false;
    _dataPtr -= _data.length;
    _data = _nextData;
    _currentOffset = _nextOffset;
    // System.out.println("processing chunk " + _nextChunkIdx);
    ++_nextChunkIdx;
    Key k = ValueArray.getChunk(_key, _nextChunkIdx);
    Value v = UKV.get(k);
    if (v != null) {
      _nextData = (_nextChunkIdx == _maxChunkIdx) ? v.get(1024) : v.get();
      _nextOffset = ValueArray.getOffset(k);
    } else
      _nextData = null;
    _length = _data.length + ((_nextData == null) ? 0 : _nextData.length);
    return true;
  }

  final public boolean isSeparator(char c) {
    return (c == _setup.separator)
        || (_setup.whiteSpaceSeparator && Character.isWhitespace(c));
  }

  public boolean hasNext() {
    if (_next)
      return true;
    if (_data == null)
      return false;
    if (_dataPtr >= _data.length && !nextData())
      return false;
    _state = STATE_INITIAL;
    _column = 0;
    _fieldStart = _dataPtr;
    _recordStart = _dataPtr;
    boolean recordFinished = false;
    try {
      // try to parse
      while (!recordFinished) {
        if (_dataPtr == _length && _nextData != null
            && _nextData.length == 1024) {
          // deal with the improbable case of record crossing boundary by more
          // than 1024 bits
          Key k = ValueArray.getChunk(_key, _nextChunkIdx);
          Value v = UKV.get(k);
          if (v != null) {
            _nextData = v.get();
            _length = _data.length + _nextData.length;
          }
        }
        if (_dataPtr == _length)
          return false; // reach the end of data
        char c = (char) ((_dataPtr < _data.length) ? _data[_dataPtr]
            : _nextData[_dataPtr - _data.length]);
        switch (_state) {
        case STATE_INITIAL: // start of a field or only whitespace read
          switch (c) {
          case '\r':
            _state = STATE_ENDLINE;
            _fieldEnd = _dataPtr;
            break;
          case '\n':
            recordFinished = endRecord();
            break;
          case '"':
            _state = STATE_QUOTED;
            _fieldStart = _dataPtr + 1;
            break;
          default:
            _fieldEnd = _dataPtr;
            if (isSeparator(c)) {
              endField();
            } else if (!Character.isWhitespace(c))
              _state = STATE_NONQUOTEDFIELD;
          }
          break;
        case STATE_QUOTED:
          if (c == '"') {
            _state = STATE_ENDQUOTE;
            _fieldEnd = _dataPtr;
          }
          break;
        case STATE_ENDQUOTE:
          if (c == '"') {
            _state = STATE_QUOTED;
            break;
          } else
            _state = STATE_AFTER_QUOTED; // fall through
        case STATE_AFTER_QUOTED:
          if (c == '\r') {
            _state = STATE_ENDLINE;
            endField();
          } else if (c == '\n') {
            endField();
            recordFinished = endRecord();
          } else if (isSeparator(c)) {
            endField();
          } else if (!Character.isWhitespace(c)) {

            _state = STATE_NONQUOTEDFIELD; // probably malformed csv!
          }
          break;
        case STATE_NONQUOTEDFIELD:
          // should not happen but just ignore it
          // if (c == '"')
          // throw new Error(
          // "field quoted after non-whitespace characters have been read");
          if (c == '\r') {            
            _state = STATE_ENDLINE;
            _fieldEnd = _dataPtr;
            endField();
          } else if (c == '\n') {
            _fieldEnd = _dataPtr;
            endField();
            recordFinished = endRecord();
          } else if (isSeparator(c)) {
            _fieldEnd = _dataPtr;
            endField();
          }
          break;
        case STATE_ENDLINE:          
          recordFinished = endRecord();
          if (c != '\n')
            continue; // do not advance pointer in this case!
          break;
        default:
          assert false;
        }
        ++_dataPtr;
      }
    } catch (Exception e) {
      throw new Error(e);
    }
    return (_next = true);
  }

  public T next() {
    if (!hasNext())
      throw new NoSuchElementException();
    _fresh = false;
    _next = false;
    return _csvRecord;
  }

  public void remove() {
    throw new UnsupportedOperationException();
  }

  public Iterator<T> iterator() {
    if (!_fresh && _data != null) {
      if ((_key != null) && (_key._kb[0] == Key.ARRAYLET_CHUNK)
          && (_nextChunkIdx > (_minChunkIdx + 1))) { // do we need to re-read
                                                     // beginning of the data?
        Value v = UKV.get(_key);
        if (v == null)
          throw new Error("value disapeared?");
        _data = v.get();
        _nextChunkIdx = _minChunkIdx + 1;
        Key k = ValueArray.getChunk(_key, _nextChunkIdx);
        v = UKV.get(k);
        if (v != null)
          _nextData = v.get(); // nextData can not be the last chunk in this
                               // case, already checked by the branch
      }
    }
    return this;
  }
  
  public int ncolumns() {
    return Array.getLength(_csvRecord);
  }
  
  public int maxColumn() {
    return _maxColumn;
  }  
}