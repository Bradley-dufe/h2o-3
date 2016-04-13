package water.fvec;

import com.google.common.base.Charsets;
import water.AutoBuffer;
import water.Futures;
import water.H2O;
import water.MemoryManager;
import water.parser.BufferedString;
import water.util.PrettyPrint;
import water.util.UnsafeUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

// An uncompressed chunk of data, supporting an append operation
public class NewChunk extends Chunk {

  public void alloc_mantissa(int sparseLen) {_ms = new Mantissas(sparseLen);}

  public void alloc_exponent(int sparseLen) {_xs = new Exponents(sparseLen);}

  public int is(int i) { return _is[i];}

  public void set_is(int i, int val) {_is[i] = val;}

  public void alloc_nums(int len) { _ms = new Mantissas(len); _xs = new Exponents(len);}


  public static class Exponents {
    public Exponents(int cap){}
    byte [] _vals1;
    int  [] _vals4;
    int _c;

    private void setRaw(int idx, byte b) {
      while(idx >= _vals1.length)
        _vals1 = Arrays.copyOf(_vals1,_vals1.length*2);
      _vals1[idx] = b;
      if(_c <= idx) _c = idx+1;
    }
    private void setRaw(int idx, int x) {
      while(idx >= _vals4.length)
        _vals4 = Arrays.copyOf(_vals4,_vals4.length*2);
      _vals4[idx] = x;
      if(_c <= idx) _c = idx+1;
    }

    private void alloc_data(int size, int val){
      // need to allocate new array, has to match the lenght of mantissa
      int len = 4;
      while(len < size) len = len << 1;
      byte b = (byte)val;
      if(b == val && b != CATEGORICAL_1)
        _vals1 = MemoryManager.malloc1(len);
      else
        _vals4 = MemoryManager.malloc4(len);
    }

    public void add(int v) {set(_c,v);}


    public void set(int idx, int x) {
      if(_vals1 == null && _vals4 == null) {
        if(x == 0) {
          if(idx >= _c) _c = idx+1;
          return;
        }
        alloc_data(idx,x);
      }
      if(_vals1 != null){
        byte b = (byte)x;
        if(x == b && b > Byte.MIN_VALUE-1) {
          setRaw(idx,b);
          return;
        } else {
          // need to switch to 4 byte values
          int len = _vals1.length;
          if(_vals1.length == _c) len = 2*len;
          _vals4 = MemoryManager.malloc4(len);
          for (int i = 0; i < _c; ++i)
            _vals4[i] = (_vals1[i] == CATEGORICAL_1)?CATEGORICAL_2:_vals1[i];
          _vals1 = null;
        }
      }
      setRaw(idx,x);
    }
    public int get(int id){
      if(_vals1 == null && null == _vals4) return 0;
      if(_vals1 != null) {
        int x = _vals1[id];
        if(x == CATEGORICAL_1)
          x = CATEGORICAL_2;
        return x;
      }
      return _vals4[id];
    }
    public boolean isCategorical(int i) { return _vals1 !=  null && _vals1[i] == CATEGORICAL_1 || _vals4 != null && _vals4[i] == CATEGORICAL_2;}

    private static byte CATEGORICAL_1 = Byte.MIN_VALUE;
    private static int  CATEGORICAL_2 = Integer.MIN_VALUE;

    public void addCategorical() {
      if(_vals1 == null && _vals4 == null) alloc_data(_c,0);
      if(_vals1 != null) setRaw(_c,CATEGORICAL_1);
      else setRaw(_c,CATEGORICAL_2);
    }

    public void move(int to, int from) {
      if(_vals1 == null && null == _vals4) return;
      if(_vals1 != null)
        _vals1[to] = _vals1[from];
      else
        _vals4[to] = _vals4[from];
    }
  }

  public static class Mantissas {
    byte [] _vals1;
    int  [] _vals4;
    long [] _vals8;
    public int _nas;
    int _c;
    int _nzs;

    public Mantissas(int cap) {_vals1 = MemoryManager.malloc1(cap);}

    public void add(long l) {set(_c,l);}

    public void set(int idx, long l) {
      if(idx > _c) throw new IndexOutOfBoundsException();
      long old;
      if(_vals1 != null) { // check if we fit withing single byte
        byte b = (byte)l;
        if(b == l && b != NA_1) {
          old = setRaw(idx,b);
          if(old == NA_1) old = NA_8;
        } else {
          int i = (int)l;
          if(i == l && i != NA_4) {
            switchToInts();
            old = setRaw(idx,i);
            if(old == NA_4) old = NA_8;
          } else {
            switchToLongs();
            old = setRaw(idx,l);
          }
        }
      } else  if(_vals4 != null) {
        int i = (int)l;
        if(i != l || i == NA_4) {
          switchToLongs();
          old = setRaw(idx,l);
          if(old == NA_4) old = NA_8;
        } else
          old = setRaw(idx,i);
      } else
        old = setRaw(idx,l);
      if (old != l) {
        if (old == 0) ++_nzs;
        else if(l == 0) --_nzs;
        else if (l == NA_8)
          --_nas;
      }
    }
    public long get(int id) {
      if(id >= _c) throw new IndexOutOfBoundsException();
      if(_vals1 != null) {
        long l = _vals1[id];
        return (l == NA_1)?NA_8:l;
      }
      if(_vals4 != null) {
        long l = _vals4[id];
        return (l == NA_4)?NA_8:l;
      }
      return _vals8[id];
    }

    public void switchToInts() {
      int len = _vals1.length;
      if(len < _c) len *= 2;
      _vals4 = MemoryManager.malloc4(len);
      if(_c > 0)
        for(int i = 0; i < Math.min(_vals1.length,_c); ++i)
          _vals4[i] = _vals1[i] == NA_1?NA_4:_vals1[i];
      _vals1 = null;
    }

    public void switchToLongs() {
      int len = Math.max(_vals1 == null?0:_vals1.length,_vals4 == null?0:_vals4.length);
      int newlen = len;
      if(len < _c) newlen *= 2;
      _vals8 = MemoryManager.malloc8(newlen);
      int n = Math.min(_c,len);
      if(_c > 0) {
        if(_vals1 != null)
          for(int i = 0; i < n; ++i)
            _vals8[i] = _vals1[i] == NA_1?NA_8:_vals1[i];
        else if(_vals4 != null) {
          for(int i = 0; i < n; ++i)
            _vals8[i] = _vals4[i] == NA_4?NA_8:_vals4[i];
        }
      }
      _vals1 = null;
      _vals4 = null;
    }

    private byte setRaw(int idx, byte val) {
      while(idx == _vals1.length)
        _vals1 = Arrays.copyOf(_vals1,_vals1.length*2);
      byte old = _vals1[idx];
      _vals1[idx] = val;
      if(_c <= idx) _c = idx+1;
      assert _c <= _vals1.length;
      return old;
    }
    private int setRaw(int idx, int val) {
      while(idx >= _vals4.length)
        _vals4 = Arrays.copyOf(_vals4,_vals4.length*2);
      int old = _vals4[idx];
      _vals4[idx] = val;
      if(_c <= idx) _c = idx+1;
      return old;
    }
    private long setRaw(int idx, long val) {
      while(idx >= _vals8.length)
        _vals8 = Arrays.copyOf(_vals8,_vals8.length*2);
      long old = _vals8[idx];
      _vals8[idx] = val;
      if(_c <= idx) _c = idx+1;
      return old;
    }

    public void setNA(int i) {
      if(i > _c) throw new IndexOutOfBoundsException();
      if (_vals1 != null) {
        byte old = setRaw(i,NA_1);
        if (old != NA_1) ++_nas;
        if(old == 0) ++_nzs;
        assert _c <= _vals1.length;
      } else if (_vals4 != null) {
        int old = setRaw(i,NA_4);
        if (old != NA_4) ++_nas;
        if (old == 0) ++_nzs;
      } else {
        long old = setRaw(i,NA_8);
        if (old != NA_8) ++_nas;
        if (old == 0) ++_nzs;
      }

    }

    public void addNA() {setNA(_c);}

    public void move(int to, int from) {
      if(to >= _c) throw new IndexOutOfBoundsException();
      if(from >= _c) throw new IndexOutOfBoundsException();
      if(_vals1 != null) {
        _vals1[to] = _vals1[from];
      } else if(_vals4 != null) {
        _vals4[to] = _vals4[from];
      } else
        _vals8[to] = _vals8[from];
    }

    public int len() {
      return _vals1 != null?_vals1.length:_vals4 != null?_vals4.length:_vals8.length;
    }

    final byte NA_1 = Byte.MIN_VALUE;
    final int  NA_4 = Integer.MIN_VALUE;
    final long NA_8 = Long.MIN_VALUE;

    public boolean isNA(int idx) {
      if(idx >= _c) throw new IndexOutOfBoundsException();
      if(_vals1 != null) return _vals1[idx] == NA_1;
      if(_vals4 != null) return _vals4[idx] == NA_4;
      if(_vals8 != null) return _vals8[idx] == NA_8;
      throw new IllegalStateException();
    }
  }

  public final int _cidx;
  // We can record the following (mixed) data types:
  // 1- doubles, in _ds including NaN for NA & 0; _ls==_xs==null
  // 2- scaled decimals from parsing, in _ls & _xs; _ds==null
  // 3- zero: requires _ls==0 && _xs==0
  // 4- NA: _ls==Long.MAX_VALUE && _xs==Integer.MIN_VALUE || _ds=NaN
  // 5- Categorical: _xs==(Integer.MIN_VALUE+1) && _ds==null
  // 6- Str: _ss holds appended string bytes (with trailing 0), _is[] holds offsets into _ss[]
  // Chunk._len is the count of elements appended
  // Sparse: if _sparseLen != _len, then _ls/_ds are compressed to non-zero's only,
  // and _xs is the row number.  Still _len is count of elements including
  // zeros, and _sparseLen is count of non-zeros.
  private transient Mantissas _ms;   // Mantissa
  private transient Exponents _xs;   // Exponent, or if _ls==0, NA or Categorical or Rows
  private transient int    _id[];   // Indices (row numbers) of stored values, used for sparse
  private transient double _ds[];   // Doubles, for inflating via doubles
  public transient byte[]   _ss;   // Bytes of appended strings, including trailing 0
  private transient int    _is[];   // _is[] index of strings - holds offsets into _ss[]. _is[i] == -1 means NA/sparse

  int   [] alloc_indices(int l)  { return _id = MemoryManager.malloc4(l); }
  public double[] alloc_doubles(int l)  {
    if(_ms != null && _ms._c == 0) {
      _ms = null;
      _xs = null;
    }
    return _ds = MemoryManager.malloc8d(l);
  }
  int   [] alloc_str_indices(int l) {
    if(_ms != null && _ms._c == 0) {
      _ms = null;
      _xs = null;
    }
    return _is = MemoryManager.malloc4(l);
  }

  final protected int   []  indices() { return _id; }
  final protected double[]  doubles() { return _ds; }

  @Override public boolean isSparseZero() { return sparseZero(); }
  public boolean _sparseNA = false;
  @Override public boolean isSparseNA() {return sparseNA();}
  void setSparseNA() {_sparseNA = true;}

  public int _sslen;                   // Next offset into _ss for placing next String

  public int _sparseLen;
  int set_sparseLen(int l) {
    return this._sparseLen = l;
  }
  @Override public int sparseLenZero() { return _sparseNA ? _len : _sparseLen;}
  @Override public int sparseLenNA() { return _sparseNA ? _sparseLen : _len; }

  private int _naCnt=-1;                // Count of NA's   appended
  protected int naCnt() { return _naCnt; }               // Count of NA's   appended
  private int _catCnt;                  // Count of Categorical's appended
  private int _strCnt;                  // Count of string's appended
  private int _nzCnt;                   // Count of non-zero's appended
  private int _uuidCnt;                 // Count of UUIDs

  public int _timCnt = 0;
  protected static final int MIN_SPARSE_RATIO = 8;
  private int _sparseRatio = MIN_SPARSE_RATIO;
  public boolean _isAllASCII = true; //For cat/string col, are all characters in chunk ASCII?

  public NewChunk( Vec vec, int cidx ) {
    _vec = vec; _cidx = cidx;
    _ms = new Mantissas(4);
    _xs = new Exponents(4);
  }

  public NewChunk( Vec vec, int cidx, boolean sparse ) {
    _vec = vec; _cidx = cidx;
    _ms = new Mantissas(4);
    _xs = new Exponents(4);
    if(sparse) _id = new int[4];
  }

  public NewChunk(double [] ds) {
    _cidx = -1;
    _vec = null;
    setDoubles(ds);
  }
  public NewChunk( Vec vec, int cidx, long[] mantissa, int[] exponent, int[] indices, double[] doubles) {
    _vec = vec; _cidx = cidx;
    _ms = new Mantissas(mantissa.length);
    _xs = new Exponents(exponent.length);
    for(int i = 0; i < mantissa.length; ++i) {
      _ms.add(mantissa[i]);
      _xs.add(exponent[i]);
    }
    _id = indices;
    _ds = doubles;
    if (_ms != null && _sparseLen==0) set_sparseLen(set_len(_ms._c));
    if (_xs != null && _sparseLen==0) set_sparseLen(set_len(_xs._c));
    if (_id != null && _sparseLen==0) set_sparseLen(set_len(_id.length));
    if (_ds != null && _sparseLen==0) set_sparseLen(set_len(_ds.length));
  }

  // Constructor used when inflating a Chunk.
  public NewChunk( Chunk c ) {
    this(c._vec, c.cidx());
    _start = c._start;
  }

  // Pre-sized newchunks.
  public NewChunk( Vec vec, int cidx, int len ) {
    this(vec,cidx);
    _ds = new double[len];
    Arrays.fill(_ds, Double.NaN);
    set_sparseLen(set_len(len));
  }

  public NewChunk setSparseRatio(int s) {
    _sparseRatio = s;
    return this;
  }

  public void setDoubles(double[] ds) {
    _ds = ds;
    _sparseLen = _len = ds.length;
    _ms = null;
    _xs = null;
  }

  public void set_vec(Vec vec) { _vec = vec; }


  public final class Value {
    int _gId; // row number in dense (ie counting zeros)
    int _lId; // local array index of this value, equal to _gId if dense

    public Value(int lid, int gid){_lId = lid; _gId = gid;}
    public final int rowId0(){return _gId;}
    public void add2Chunk(NewChunk c){add2Chunk_impl(c,_lId);}
  }

  private transient BufferedString _bfstr = new BufferedString();

  private void add2Chunk_impl(NewChunk c, int i){
    if (_ds == null && _ss == null) {
      if (isNA2(i)) c.addNA();
      else c.addNum(_ms.get(i),_xs.get(i));
    } else {
      if (_ms != null) {
        c.addUUID(_ms.get(i), Double.doubleToRawLongBits(_ds[i]));
      } else if (_ss != null) {
        int sidx = _is[i];
        int nextNotNAIdx = i+1;
        // Find next not-NA value (_is[idx] != -1)
        while (nextNotNAIdx < _is.length && _is[nextNotNAIdx] == -1) nextNotNAIdx++;
        int slen = nextNotNAIdx < _is.length ? _is[nextNotNAIdx]-sidx : _sslen - sidx;
        // null-BufferedString represents NA value
        BufferedString bStr = sidx == -1 ? null : _bfstr.set(_ss, sidx, slen);
        c.addStr(bStr);
      } else
        c.addNum(_ds[i]);
    }
  }
  public void add2Chunk(NewChunk c, int i){
    if(!isSparseNA() && !isSparseZero())
      add2Chunk_impl(c,i);
    else {
      int j = Arrays.binarySearch(_id,0,_sparseLen,i);
      if(j >= 0)
        add2Chunk_impl(c,j);
      else if(isSparseNA())
        c.addNA();
      else
        c.addNum(0,0);
    }
  }

  public Iterator<Value> values(){ return values(0,_len);}
  public Iterator<Value> values(int fromIdx, int toIdx){
    final int lId, gId;
    final int to = Math.min(toIdx, _len);

    if(_id != null){
      int x = Arrays.binarySearch(_id,0, _sparseLen,fromIdx);
      if(x < 0) x = -x -1;
      lId = x;
      gId = x == _sparseLen ? _len :_id[x];
    } else
      lId = gId = fromIdx;
    final Value v = new Value(lId,gId);
    final Value next = new Value(lId,gId);
    return new Iterator<Value>(){
      @Override public final boolean hasNext(){return next._gId < to;}
      @Override public final Value next(){
        if(!hasNext())throw new NoSuchElementException();
        v._gId = next._gId; v._lId = next._lId;
        next._lId++;
        if(_id != null) next._gId = next._lId < _sparseLen ?_id[next._lId]: _len;
        else next._gId++;
        return v;
      }
      @Override
      public void remove() {throw new UnsupportedOperationException();}
    };
  }

  // Heuristic to decide the basic type of a column
  byte type() {
    if( _naCnt == -1 ) {        // No rollups yet?
      int nas=0, es=0, nzs=0, ss=0;
      if( _ds != null && _ms != null ) { // UUID?
        for(int i = 0; i< _sparseLen; i++ )
          if( _xs != null && _xs.get(i)==Integer.MIN_VALUE )  nas++;
          else if( _ds[i] !=0 || _ms.get(i) != 0 ) nzs++;
        _uuidCnt = _len -nas;
      } else if( _ds != null ) { // Doubles?
        assert _xs==null;
        for(int i = 0; i < _sparseLen; ++i) {
          if( Double.isNaN(_ds[i]) ) nas++; 
          else if( _ds[i]!=0 ) nzs++;
        }
      } else {
        if( _ms != null && _ms._c > 0) // Longs and categoricals?
          for(int i = 0; i< _sparseLen; i++ )
            if( isNA2(i) ) nas++;
            else {
              if( isCategorical2(i)   ) es++;
              if( _ms.get(i) != 0 ) nzs++;
            }
        if( _is != null )  // Strings
          for(int i = 0; i< _sparseLen; i++ )
            if( isNA2(i) ) nas++;
            else ss++;
      }
      if (_sparseNA) nas += (_len - _sparseLen);
      _nzCnt=nzs;  _catCnt =es;  _naCnt=nas; _strCnt = ss;
    }
    // Now run heuristic for type
    if(_naCnt == _len)          // All NAs ==> NA Chunk
      return Vec.T_BAD;
    if(_strCnt > 0)
      return Vec.T_STR;
    if(_catCnt > 0 && _catCnt + _naCnt == _len)
      return Vec.T_CAT; // All are Strings+NAs ==> Categorical Chunk
    // UUIDs?
    if( _uuidCnt > 0 ) return Vec.T_UUID;
    // Larger of time & numbers
    int nums = _len -_naCnt-_timCnt;
    return _timCnt >= nums ? Vec.T_TIME : Vec.T_NUM;
  }

  //what about sparse reps?
  protected final boolean isNA2(int idx) {
    if (isUUID()) return _ms.get(idx)==C16Chunk._LO_NA && Double.doubleToRawLongBits(_ds[idx])==C16Chunk._HI_NA;
    if (isString()) return _is[idx] == -1;
    return (_ds == null) ? _ms.isNA(idx) : Double.isNaN(_ds[idx]);
  }
  protected final boolean isCategorical2(int idx) {
    return _xs!=null && _xs.isCategorical(idx);
  }
  protected final boolean isCategorical(int idx) {
    if(_id == null)return isCategorical2(idx);
    int j = Arrays.binarySearch(_id,0, _sparseLen,idx);
    return j>=0 && isCategorical2(j);
  }

  public void addCategorical(int e) {
    if(_ms == null || _ms.len() == _sparseLen)
      append2slow();
    _ms.add(e);
    _xs.addCategorical();
    if(_id != null) _id[_sparseLen] = _len;
    ++_sparseLen;
    assert _ms._c == _sparseLen:"_ms._c = " + _ms._c +", sparseLen = " + _sparseLen;
    ++_len;
  }
  public void addNA() {
    if( isUUID() ) addUUID(C16Chunk._LO_NA, C16Chunk._HI_NA);
    else if( isString() ) addStr(null);
    else if (_ds != null) addNum(Double.NaN);
    else {
      if(!_sparseNA && _ms.len() == _sparseLen)
        append2slow();
      if(!_sparseNA) {
        _ms.addNA();
        _xs.add(0);
        if(_id != null)  _id[_sparseLen] = _len;
        ++_sparseLen;
      }
      assert _ms._c == _sparseLen:"_ms._c = " + _ms._c +", sparseLen = " + _sparseLen;
      ++_len;
    }
    assert _ms == null || _ms._c == _sparseLen:"_ms._c = " + _ms._c +", sparseLen = " + _sparseLen;
  }

  public void addNum (long val, int exp) {
    if( isUUID() || isString() ) addNA();
    else if(_ds != null) {
      assert _ms == null;
      addNum(val*PrettyPrint.pow10(exp));
      assert _ms == null || _ms._c == _sparseLen;
    } else {
      if( val == 0 ) exp = 0;// Canonicalize zero
      boolean predicate = _sparseNA ? (val != Long.MAX_VALUE || exp != Integer.MIN_VALUE): val != 0;
      int [] id = _id;
      if(_id == null || predicate) {
        if(_ms == null || _ms.len() == _sparseLen)
          append2slow();
        int len = _ms.len();
        int slen = _sparseLen;
        if(_id == null || predicate) {
          long t;                // Remove extra scaling
          while (exp < 0 && exp > -9999999 && (t = val / 10) * 10 == val) {
            val = t;
            exp++;
          }
          assert _ms._c == _sparseLen : "_ms._c = " + _ms._c + ", sparseLen = " + _sparseLen;
          _ms.add(val);
          _xs.add(exp);
          assert _id == null || _id.length == _ms.len():"id.len = " + _id.length + ", ms.len = " + _ms.len() +", old ms.len = " + len + ", sparseLen = " + slen;
          if(_id != null)_id[_sparseLen] = _len;
          _sparseLen++;
          assert _ms._c == _sparseLen : "_ms._c = " + _ms._c + ", sparseLen = " + _sparseLen;
        }
      }
      _len++;
    }
  }
  // Fast-path append double data
  public void addNum(double d) {
    if( isUUID() || isString() ) { addNA(); return; }
    boolean predicate = _sparseNA ? !Double.isNaN(d) : d != 0;
    if(_id == null || predicate) {
      if(_ms != null)switch_to_doubles();
      //if ds not big enough
      if( _ds == null || _sparseLen >= _ds.length ) {
        append2slowd();
        // call addNum again since append2slowd might have flipped to sparse
        addNum(d);
        assert _sparseLen <= _len;
        return;
      }
      if(_id != null)_id[_sparseLen] = _len;
      _ds[_sparseLen] = d;
      _sparseLen++;
    }
    _len++;
    assert _sparseLen <= _len;
  }

  private void append_ss(String str) {
    byte[] bytes = str == null ? new byte[0] : str.getBytes(Charsets.UTF_8);

    // Allocate memory if necessary
    if (_ss == null)
      _ss = MemoryManager.malloc1((bytes.length+1) * 4);
    while (_ss.length < (_sslen + bytes.length+1))
      _ss = MemoryManager.arrayCopyOf(_ss,_ss.length << 1);

    // Copy bytes to _ss
    for (byte b : bytes) _ss[_sslen++] = b;
    _ss[_sslen++] = (byte)0; // for trailing 0;
  }

  private void append_ss(BufferedString str) {
    int strlen = str.length();
    int off = str.getOffset();
    byte b[] = str.getBuffer();

    if (_ss == null) {
      _ss = MemoryManager.malloc1((strlen + 1) * 4);
    }
    while (_ss.length < (_sslen + strlen + 1)) {
      _ss = MemoryManager.arrayCopyOf(_ss,_ss.length << 1);
    }
    for (int i = off; i < off+strlen; i++)
      _ss[_sslen++] = b[i];
    _ss[_sslen++] = (byte)0; // for trailing 0;
  }

  // Append a string, store in _ss & _is
  public void addStr(Object str) {
    if(_id == null || str != null) {
      if(_is == null || _sparseLen >= _is.length) {
        append2slowstr();
        addStr(str);
        assert _sparseLen <= _len;
        return;
      }
      if (str != null) {
        if(_id != null)_id[_sparseLen] = _len;
        _is[_sparseLen] = _sslen;
        _sparseLen++;
        if (str instanceof BufferedString)
          append_ss((BufferedString) str);
        else // this spares some callers from an unneeded conversion to BufferedString first
          append_ss((String) str);
      } else if (_id == null) {
        _is[_sparseLen] = CStrChunk.NA;
        set_sparseLen(_sparseLen + 1);
      }
    }
    set_len(_len + 1);
    assert _sparseLen <= _len;
  }

  // TODO: FIX isAllASCII test to actually inspect string contents
  public void addStr(Chunk c, long row) {
    if( c.isNA_abs(row) ) addNA();
    else { addStr(c.atStr_abs(new BufferedString(), row)); _isAllASCII &= ((CStrChunk)c)._isAllASCII; }
  }

  public void addStr(Chunk c, int row) {
    if( c.isNA(row) ) addNA();
    else { addStr(c.atStr(new BufferedString(), row)); _isAllASCII &= ((CStrChunk)c)._isAllASCII; }
  }

  // Append a UUID, stored in _ls & _ds
  public void addUUID( long lo, long hi ) {
    if( _ms==null || _ds== null || _sparseLen >= _ms._c )
      append2slowUUID();
    _ms.add(lo);
    _ds[_sparseLen] = Double.longBitsToDouble(hi);
    set_sparseLen(_sparseLen + 1);
    set_len(_len + 1);
    assert _sparseLen <= _len;
  }
  public void addUUID( Chunk c, long row ) {
    if( c.isNA_abs(row) ) addUUID(C16Chunk._LO_NA,C16Chunk._HI_NA);
    else addUUID(c.at16l_abs(row),c.at16h_abs(row));
  }
  public void addUUID( Chunk c, int row ) {
    if( c.isNA(row) ) addUUID(C16Chunk._LO_NA,C16Chunk._HI_NA);
    else addUUID(c.at16l(row),c.at16h(row));
  }

  public final boolean isUUID(){return _ms != null && _ds != null; }
  public final boolean isString(){return _is != null; }
  public final boolean sparseZero(){return _id != null && !_sparseNA;}
  public final boolean sparseNA() {return _id != null && _sparseNA;}

  public void addZeros(int n){
    if(!sparseZero()) for(int i = 0; i < n; ++i)addNum(0,0);
    else set_len(_len + n);
  }
  
  public void addNAs(int n) {
    if(!sparseNA())
      for (int i = 0; i <n; ++i) {
        addNA();
        if(sparseNA()) {
          set_len(_len + n - i -1);
          return;
        }

      }
    else set_len(_len + n);
  }
  
  // Append all of 'nc' onto the current NewChunk.  Kill nc.
  public void add( NewChunk nc ) {
    assert _cidx >= 0;
    assert _sparseLen <= _len;
    assert nc._sparseLen <= nc._len :"_sparseLen = " + nc._sparseLen + ", _len = " + nc._len;
    if( nc._len == 0 ) return;
    if(_len == 0){
      _ms = nc._ms; nc._ms = null;
      _xs = nc._xs; nc._xs = null;
      _id = nc._id; nc._id = null;
      _ds = nc._ds; nc._ds = null;
      _is = nc._is; nc._is = null;
      _ss = nc._ss; nc._ss = null;
      set_sparseLen(nc._sparseLen);
      set_len(nc._len);
      return;
    }
    if(nc.sparseZero() != sparseZero() || nc.sparseNA() != sparseNA()){ // for now, just make it dense
      cancel_sparse();
      nc.cancel_sparse();
    }
    if( _ds != null ) throw H2O.fail();
    for(int i = 0; i < nc._ms._c; ++i) {
      _ms.add(nc._ms.get(i));
      _xs.add(nc._xs.get(i));
    }
    if(_id != null) {
      assert nc._id != null;
      _id = MemoryManager.arrayCopyOf(_id,_xs._c);
      System.arraycopy(nc._id,0,_id, _sparseLen, nc._sparseLen);
      for(int i = _sparseLen; i < _sparseLen + nc._sparseLen; ++i) _id[i] += _len;
    } else assert nc._id == null;

    set_sparseLen(_sparseLen + nc._sparseLen);
    set_len(_len + nc._len);
    nc._ms = null;  nc._xs = null; nc._id = null; nc.set_sparseLen(nc.set_len(0));
    assert _sparseLen <= _len;
  }

  // Fast-path append long data
//  void append2( long l, int x ) {
//    boolean predicate = _sparseNA ? (l != Long.MAX_VALUE || x != Integer.MIN_VALUE): l != 0;
//    if(_id == null || predicate){
//      if(_ms == null || _sparseLen == _ms._c) {
//        append2slow();
//        // again call append2 since calling append2slow might have changed things (eg might have switched to sparse and l could be 0)
//        append2(l,x);
//        return;
//      }
//      _ls[_sparseLen] = l;
//      _xs[_sparseLen] = x;
//      if(_id  != null)_id[_sparseLen] = _len;
//      set_sparseLen(_sparseLen + 1);
//    }
//    set_len(_len + 1);
//    assert _sparseLen <= _len;
//  }

  // Slow-path append data
  private void append2slowd() {
    assert _ms==null;
    if(_ds != null && _ds.length > 0){
      if(_id == null) { // check for sparseness
        int nzs = 0; // assume one non-zero for the element currently being stored
        int nonnas = 0;
        for(double d:_ds) {
          if(d != 0)++nzs;
          if(!Double.isNaN(d))++nonnas;
        }
        if((nzs+1)*_sparseRatio < _len) {
          set_sparse(nzs,Compress.ZERO);
          assert _sparseLen == 0 || _sparseLen <= _ds.length:"_sparseLen = " + _sparseLen + ", _ds.length = " + _ds.length + ", nzs = " + nzs +  ", len = " + _len;
          assert _id.length == _ds.length;
          assert _sparseLen <= _len;
          return;
        }
        else if((nonnas+1)*_sparseRatio < _len) {
          set_sparse(nonnas,Compress.NA);
          assert _sparseLen == 0 || _sparseLen <= _ds.length:"_sparseLen = " + _sparseLen + ", _ds.length = " + _ds.length + ", nonnas = " + nonnas +  ", len = " + _len;
          assert _id.length == _ds.length;
          assert _sparseLen <= _len;
          return;
        }
      } 
      else {
        // verify we're still sufficiently sparse
        if((_sparseRatio*(_sparseLen) >> 2) > _len)  cancel_sparse();
        else _id = MemoryManager.arrayCopyOf(_id, _sparseLen << 1);
      }
      _ds = MemoryManager.arrayCopyOf(_ds, _sparseLen << 1);
    } else {
      alloc_doubles(4);
      if (_id != null) alloc_indices(4);
    }
    assert _sparseLen == 0 || _ds.length > _sparseLen :"_ds.length = " + _ds.length + ", _sparseLen = " + _sparseLen;
    assert _id == null || _id.length == _ds.length;
    assert _sparseLen <= _len;
  }
  // Slow-path append data
  private void append2slowUUID() {
    if( _ds==null && _ms!=null ) { // This can happen for columns with all NAs and then a UUID
      _xs=null;
      _ms.switchToLongs();
      _ds = MemoryManager.malloc8d(_sparseLen);
      Arrays.fill(_ms._vals8,C16Chunk._LO_NA);
      Arrays.fill(_ds,Double.longBitsToDouble(C16Chunk._HI_NA));
    }
    if( _ms != null && _ms._c > 0 ) {
      _ds = MemoryManager.arrayCopyOf(_ds, _sparseLen <<1);
    } else {
      _ms = new Mantissas(4);
      _xs = null;
      _ms.switchToLongs();
      _ds = new double[4];
    }
    assert _sparseLen == 0 || _ms._c >= _sparseLen :"_ls.length = " + _ms._c + ", _len = " + _sparseLen;
  }
  // Slow-path append string
  private void append2slowstr() {
    // In case of all NAs and then a string, convert NAs to string NAs
    if (_xs != null) {
      _xs = null; _ms = null;
      alloc_str_indices(_sparseLen);
      Arrays.fill(_is,-1);
    }

    if(_is != null && _is.length > 0){
      // Check for sparseness
      if(_id == null){
        int nzs = 0; // assume one non-null for the element currently being stored
        for( int i:_is) if( i != -1 ) ++nzs;
        if( (nzs+1)*_sparseRatio < _len)
          set_sparse(nzs, Compress.ZERO);
      } else {
        if((_sparseRatio*(_sparseLen) >> 2) > _len)  cancel_sparse();
        else _id = MemoryManager.arrayCopyOf(_id,_sparseLen<<1);
      }

      _is = MemoryManager.arrayCopyOf(_is, _sparseLen<<1);
      /* initialize the memory extension with -1s */
      for (int i = _sparseLen; i < _is.length; i++) _is[i] = -1;
    } else {
      _is = MemoryManager.malloc4 (4);
        /* initialize everything with -1s */
      for (int i = 0; i < _is.length; i++) _is[i] = -1;
      if (sparseZero()||sparseNA()) alloc_indices(4);
    }
    assert _sparseLen == 0 || _is.length > _sparseLen:"_ls.length = " + _is.length + ", _len = " + _sparseLen;

  }
  // Slow-path append data
  private void append2slow( ) {
// PUBDEV-2639 - don't die for many rows, few columns -> can be long chunks
//    if( _sparseLen > FileVec.DFLT_CHUNK_SIZE )
//      throw new ArrayIndexOutOfBoundsException(_sparseLen);
    assert _ds==null;
    if(_ms != null && _ms._c > 0){
      if(_id == null) { // check for sparseness
        int nzs = _ms._nzs;
        int nonnas = _ms._c - _ms._nas;
        if((nzs+1)*_sparseRatio < _len) {
          set_sparse(nzs,Compress.ZERO);
          assert _sparseLen == 0 || _sparseLen <= _ms._c:"_sparseLen = " + _sparseLen + ", _ls.length = " + _ms._c + ", nzs = " + nzs +  ", len = " + _len;
          assert _sparseLen <= _len;
          assert _ms._c == nzs;
          return;        
        } 
        else if((nonnas+1)*_sparseRatio < _len) {
          set_sparse(nonnas,Compress.NA);
          assert _sparseLen == 0 || _sparseLen <= _ms._c:"_sparseLen = " + _sparseLen + ", _ls.length = " + _ms._c + ", nonnas = " + nonnas +  ", len = " + _len;
          assert _id.length == _ms.len():"id.len = " + _id.length + ", ms.c = " + _ms._c;
          assert _sparseLen <= _len;
          return;        
        }
      } else {
        // verify we're still sufficiently sparse
        if(2*_sparseLen > _len)  cancel_sparse();
        else _id = MemoryManager.arrayCopyOf(_id, _id.length*2);
      }
    } else {
      _ms = new Mantissas(16);
      _xs = new Exponents(16);
      if (_id != null) alloc_indices(16);
    }
    assert _sparseLen <= _len;
  }

  // Do any final actions on a completed NewVector.  Mostly: compress it, and
  // do a DKV put on an appropriate Key.  The original NewVector goes dead
  // (does not live on inside the K/V store).
  public Chunk new_close() {
    Chunk chk = compress();
    if(_vec instanceof AppendableVec)
      ((AppendableVec)_vec).closeChunk(_cidx,chk._len);
    return chk;
  }
  public void close(Futures fs) { close(_cidx,fs); }

  private void switch_to_doubles(){
    assert _ds == null;
    double [] ds = MemoryManager.malloc8d(_sparseLen);
    for(int i = 0; i < _sparseLen; ++i)
      if(isNA2(i) || isCategorical2(i)) ds[i] = Double.NaN;
      else  ds[i] = _ms.get(i)*PrettyPrint.pow10(_xs.get(i));
    _ms = null;
    _xs = null;
    _ds = ds;
  }
  
  public enum Compress {ZERO, NA}

  //Sparsify. Compressible element can be 0 or NA. Store noncompressible elements in _ds OR _ls and _xs OR _is and 
  // their row indices in _id.
  protected void set_sparse(int num_noncompressibles, Compress sparsity_type) {
    if ((sparsity_type == Compress.ZERO && isSparseNA()) || (sparsity_type == Compress.NA && isSparseZero()))
      cancel_sparse();
    if (sparsity_type == Compress.NA) {
      _sparseNA = true;
    }
    if (_id != null && _sparseLen == num_noncompressibles && _len != 0) return;
    if (_id != null) { // we have sparse representation but some compressible elements in it!
      // can happen when setting a non-compressible element to a compressible one on sparse chunk
      int[] id = MemoryManager.malloc4(num_noncompressibles);
      int j = 0;
      if (_ds != null) {
        double[] ds = MemoryManager.malloc8d(num_noncompressibles);
        for (int i = 0; i < _sparseLen; ++i) {
          if (!is_compressible(_ds[i])) {
            ds[j] = _ds[i];
            id[j] = _id[i];
            ++j;
          }
        }
        _ds = ds;
      } else if (_is != null) {
        int[] is = MemoryManager.malloc4(num_noncompressibles);
        for (int i = 0; i < _sparseLen; i++) {
          if (_is[i] != -1) { //same test for NA sparse and 0 sparse
            is[j] = _is[i];
            id[j] = _id[i];
            ++j;
          }
        }
      } else {
        Mantissas ms = new Mantissas(num_noncompressibles);
        Exponents xs = new Exponents(num_noncompressibles);
        for (int i = 0; i < _sparseLen; ++i) {
          if (!is_compressible(_ms.get(i), _xs.get(i))) {
            ms.add(_ms.get(i));
            xs.add(_xs.get(i));
            id[j] = _id[i];
            ++j;
          }
        }
        _ms = ms;
        _xs = xs;
      }
      _id = id;
      assert j == num_noncompressibles;
      set_sparseLen(num_noncompressibles);
      if (_ms != null) {
        _ms._c = num_noncompressibles;
        assert _ms._nzs == num_noncompressibles;
      }
      if (_xs != null) _xs._c = num_noncompressibles;
      return;
    }
    assert _sparseLen == _len : "_sparseLen = " + _sparseLen + ", _len = " + _len + ", num_noncompressibles = " + num_noncompressibles;
    int cs = 0; //number of compressibles
    if (_is != null) {
      assert num_noncompressibles <= _is.length;
      _id = MemoryManager.malloc4(_is.length);
      for (int i = 0; i < _sparseLen; i++) {
        if (_is[i] == -1) cs++; //same condition for NA and 0
        else {
          _is[i - cs] = _is[i];
          _id[i - cs] = i;
        }
      }
    } else if (_ds == null) {
      if (_len == 0) {
        _ms = new Mantissas(0);
        _xs = new Exponents(0);
        _id = new int[0];
        set_sparseLen(0);
        return;
      } else {
        assert num_noncompressibles <= _sparseLen;
        _id = alloc_indices(_ms.len());
        for (int i = 0; i < _sparseLen; ++i) {
          if (is_compressible(_ms.get(i), _xs.get(i))) {
            ++cs;
          } else {
            _ms.move(i - cs, i);
            _xs.move(i - cs, i);
            _id[i - cs] = i;
          }
        }
      }
    } else {
      assert num_noncompressibles <= _ds.length;
      _id = alloc_indices(_ds.length);
      for (int i = 0; i < _sparseLen; ++i) {
        if (is_compressible(_ds[i])) ++cs;
        else if(cs > 0){
          _ds[i - cs] = _ds[i];
          _id[i - cs] = i;
        }
      }
    }
    assert cs == (_sparseLen - num_noncompressibles) : "cs = " + cs + " != " + (_sparseLen - num_noncompressibles);
    assert (sparsity_type == Compress.NA) == _sparseNA;
    set_sparseLen(num_noncompressibles);
    if (_ms != null) {
      _ms._c = num_noncompressibles;
      assert _sparseNA || _ms._nzs == num_noncompressibles:"nzs = " + _ms._nzs + ", non-compressibles = " + num_noncompressibles;
      assert !_sparseNA || _ms._nas == (_len - num_noncompressibles):"nas = " + _ms._nas + ", non-compressibles = " + num_noncompressibles + ", len = " + _len;
    }
    if (_xs != null) _xs._c = num_noncompressibles;
  }

  private boolean is_compressible(double d) {
    return _sparseNA ? Double.isNaN(d) : d == 0;
  }
  
  private boolean is_compressible(long l, int x) {
    return _sparseNA ? l == Long.MIN_VALUE : l == 0;
  }
  
  public void cancel_sparse(){
    if(_sparseLen != _len){
      if(_is != null){
        int [] is = MemoryManager.malloc4(_len);
        Arrays.fill(is, -1);
        for (int i = 0; i < _sparseLen; i++) is[_id[i]] = _is[i];
        _is = is;
      } else if(_ds == null){
        Exponents xs = new Exponents(_len);
        Mantissas ms = new Mantissas(_len);
        xs._c = _len;
        ms._c = _len;
        if (_sparseNA)
          for(int i = 0; i < _len; ++i)
            ms.setNA(i);
        for(int i = 0; i < _sparseLen; ++i){
          xs.set(_id[i],_xs.get(i));
          ms.set(_id[i],_ms.get(i));
        }
        ms._nzs = _ms._nzs;
        _xs = xs;
        _ms = ms;
      } else {
        double [] ds = MemoryManager.malloc8d(_len);
        if (_sparseNA) Arrays.fill(ds, Double.NaN);
        for(int i = 0; i < _sparseLen; ++i) ds[_id[i]] = _ds[i];
        _ds = ds;
      }
      set_sparseLen(_len);
    }
    _id = null;
    _sparseNA = false;
  }

  // Study this NewVector and determine an appropriate compression scheme.
  // Return the data so compressed.
  public Chunk compress() {
    assert _ms == null || _sparseLen == _ms._c:"sparseLen = " + _sparseLen + ", _c = " + _ms._c;
    assert _xs == null || _sparseLen == _xs._c:"sparseLen = " + _sparseLen + ", xs.x = " + _xs._c;
    Chunk res = compress2();
    byte type = type();
    assert _vec == null ||  // Various testing scenarios do not set a Vec
      type == _vec._type || // Equal types
      // Allow all-bad Chunks in any type of Vec
      type == Vec.T_BAD ||
      // Specifically allow the NewChunk to be a numeric type (better be all
      // ints) and the selected Vec type an categorical - whose String mapping
      // may not be set yet.
      (type==Vec.T_NUM && _vec._type==Vec.T_CAT) ||
      // Another one: numeric Chunk and Time Vec (which will turn into all longs/zeros/nans Chunks)
      (type==Vec.T_NUM && _vec._type == Vec.T_TIME && !res.hasFloat())
      : "NewChunk has type "+Vec.TYPE_STR[type]+", but the Vec is of type "+_vec.get_type_str();
    assert _len == res._len : "NewChunk has length "+_len+", compressed Chunk has "+res._len;
    // Force everything to null after compress to free up the memory.  Seems
    // like a non-issue in the land of GC, but the NewChunk *should* be dead
    // after this, but might drag on.  The arrays are large, and during a big
    // Parse there's lots and lots of them... so free early just in case a GC
    // happens before the drag-time on the NewChunk finishes.
    _id = null;
    _xs = null;
    _ds = null;
    _ms = null;
    _is = null;
    _ss = null;
    return res;
  }

  private static long leRange(long lemin, long lemax){
    if(lemin < 0 && lemax >= (Long.MAX_VALUE + lemin))
      return Long.MAX_VALUE; // if overflow return 64 as the max possible value
    long res = lemax - lemin;
    assert res >= 0;
    return res;
  }

  private Chunk compress2() {
    // Check for basic mode info: all missing or all strings or mixed stuff
    byte mode = type();
    if( mode==Vec.T_BAD ) // ALL NAs, nothing to do
      return new C0DChunk(Double.NaN, _len);
    if( mode==Vec.T_STR )
      return new CStrChunk(_sslen, _ss, _sparseLen, _len, _is, _isAllASCII);
    boolean rerun=false;
    if(mode == Vec.T_CAT) {
      for(int i = 0; i< _sparseLen; i++ )
        if(isCategorical2(i))
          _xs.set(i,0);
        else if(!isNA2(i)){
          setNA_impl2(i);
          ++_naCnt;
        }
        // Smack any mismatched string/numbers
    } else if( mode == Vec.T_NUM ) {
      for(int i = 0; i< _sparseLen; i++ )
        if(isCategorical2(i)) {
          setNA_impl2(i);
          rerun = true;
        }
    }
    if( rerun ) { _naCnt = -1;  type(); } // Re-run rollups after dropping all numbers/categoricals

    boolean sparse = false;
    boolean na_sparse = false;
    // sparse? treat as sparse iff fraction of noncompressed elements is less than 1/MIN_SPARSE_RATIO
    if(_sparseRatio*(_naCnt + _nzCnt) < _len) {
      set_sparse(_naCnt + _nzCnt, Compress.ZERO);
      sparse = true;
    } else if(_sparseRatio*(_len - _naCnt) < _len){
      set_sparse(_len - _naCnt, Compress.NA);
      na_sparse = true;
    } else if (_sparseLen != _len)
      cancel_sparse();
    
    // If the data is UUIDs there's not much compression going on
    if( _ds != null && _ms != null )
      return chunkUUID();
    // cut out the easy all NaNs case; takes care of constant na_sparse
    if(_naCnt == _len) return new C0DChunk(Double.NaN,_len);
    // If the data was set8 as doubles, we do a quick check to see if it's
    // plain longs.  If not, we give up and use doubles.
    if( _ds != null ) {
      int i; // check if we can flip to ints
      for (i=0; i < _sparseLen; ++i)
        if (!Double.isNaN(_ds[i]) && (double) (long) _ds[i] != _ds[i])
          break;
      boolean isInteger = i == _sparseLen;
      boolean isConstant = !(sparse || na_sparse) || _sparseLen == 0;
      double constVal = 0;
      if (!(sparse || na_sparse)) { // check the values, sparse with some nonzeros can not be constant - has 0s and (at least 1) nonzero
        constVal = _ds[0];
        for(int j = 1; j < _len; ++j)
          if(_ds[j] != constVal) {
            isConstant = false;
            break;
          }
      }
      if(isConstant)
        return isInteger? new C0LChunk((long)constVal, _len): new C0DChunk(constVal,_len);
      if(!isInteger) {
        if (sparse) return new CXDChunk(_len, 8, bufD(8));
        else if (na_sparse) return new CNAXDChunk(_len, 8, bufD(8));
        else return chunkD();
      }
      // Else flip to longs
      _ms = new Mantissas(_ds.length);
      _xs = new Exponents(_ds.length);
      double [] ds = _ds;
      _ds = null;
      final int naCnt = _naCnt;
      for(i=0; i< _sparseLen; i++ )   // Inject all doubles into longs
        if( Double.isNaN(ds[i]) ) {
          _ms.addNA();
        } else {
          _ms.add((long)ds[i]);
          _xs.add(0);
        }
      // setNA_impl2 will set _naCnt to -1!
      // we already know what the naCnt is (it did not change!) so set it back to correct value
      _naCnt = naCnt;
    }

    // IF (_len > _sparseLen) THEN Sparse
    // Check for compressed *during appends*.  Here we know:
    // - No specials; _xs[]==0.
    // - No floats; _ds==null
    // - NZ length in _sparseLen, actual length in _len.
    // - Huge ratio between _len and _sparseLen, and we do NOT want to inflate to
    //   the larger size; we need to keep it all small all the time.
    // - Rows in _xs

    // Data in some fixed-point format, not doubles
    // See if we can sanely normalize all the data to the same fixed-point.
    int  xmin = Integer.MAX_VALUE;   // min exponent found
    boolean floatOverflow = false;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    int p10iLength = PrettyPrint.powers10i.length;
    long llo=Long   .MAX_VALUE, lhi=Long   .MIN_VALUE;
    int  xlo=Integer.MAX_VALUE, xhi=Integer.MIN_VALUE;

    for(int i = 0; i< _sparseLen; i++ ) {
      if( isNA2(i) ) continue;
      long l = _ms.get(i);
      int  x = _xs.get(i);
      if( x==Integer.MIN_VALUE) x=0; // Replace categorical flag with no scaling
      assert l!=0 || x==0:"l == 0 while x = " + x + " ms = " + _ms.toString();      // Exponent of zero is always zero
      long t;                   // Remove extra scaling
      while( l!=0 && (t=l/10)*10==l ) { l=t; x++; }
      // Compute per-chunk min/max
      double d = l*PrettyPrint.pow10(x);
      if( d < min ) { min = d; llo=l; xlo=x; }
      if( d > max ) { max = d; lhi=l; xhi=x; }
      floatOverflow = l < Integer.MIN_VALUE+1 || l > Integer.MAX_VALUE;
      xmin = Math.min(xmin,x);
    }
    if(sparse){ // sparse?  then compare vs implied 0s
      if( min > 0 ) { min = 0; llo=0; xlo=0; }
      if( max < 0 ) { max = 0; lhi=0; xhi=0; }
      xmin = Math.min(xmin,0);
    }
    // Constant column?
    if( _naCnt==0 && (min==max)) {
      if (llo == lhi && xlo == 0 && xhi == 0)
        return new C0LChunk(llo, _len);
      else if ((long)min == min)
        return new C0LChunk((long)min, _len);
      else
        return new C0DChunk(min, _len);
    }

    // Compute min & max, as scaled integers in the xmin scale.
    // Check for overflow along the way
    boolean overflow = ((xhi-xmin) >= p10iLength) || ((xlo-xmin) >= p10iLength);
    long lemax=0, lemin=0;
    if( !overflow ) {           // Can at least get the power-of-10 without overflow
      long pow10 = PrettyPrint.pow10i(xhi-xmin);
      lemax = lhi*pow10;
      // Hacker's Delight, Section 2-13, checking overflow.
      // Note that the power-10 is always positive, so the test devolves this:
      if( (lemax/pow10) != lhi ) overflow = true;
      // Note that xlo might be > xmin; e.g. { 101e-49 , 1e-48}.
      long pow10lo = PrettyPrint.pow10i(xlo-xmin);
      lemin = llo*pow10lo;
      if( (lemin/pow10lo) != llo ) overflow = true;
    }

    // Boolean column?
    if (max == 1 && min == 0 && xmin == 0 && !overflow) {
      if(sparse) { // Very sparse?
        return  _naCnt==0
          ? new CX0Chunk(_len, bufS(0))// No NAs, can store as sparse bitvector
          : new CXIChunk(_len, 1,bufS(1)); // have NAs, store as sparse 1byte values
      }
      if(na_sparse) return new CNAXIChunk(_len, 1, bufS(1));
      int bpv = _catCnt +_naCnt > 0 ? 2 : 1;   // Bit-vector
      byte[] cbuf = bufB(bpv);
      return new CBSChunk(cbuf, cbuf[0], cbuf[1]);
    }

    final boolean fpoint = xmin < 0 || min < Long.MIN_VALUE || max > Long.MAX_VALUE;

    if( sparse ) {
      if(fpoint) return new CXDChunk(_len,8,bufD(8));
      int sz = 8;
      if( Short.MIN_VALUE <= min && max <= Short.MAX_VALUE ) sz = 2;
      else if( Integer.MIN_VALUE <= min && max <= Integer.MAX_VALUE ) sz = 4;
      return new CXIChunk(_len,sz,bufS(sz));
    }
    if( na_sparse ) {
      if(fpoint) return new CNAXDChunk(_len,8,bufD(8));
      int sz = 8;
      if( Short.MIN_VALUE <= min && max <= Short.MAX_VALUE ) sz = 2;
      else if( Integer.MIN_VALUE <= min && max <= Integer.MAX_VALUE ) sz = 4;
      return new CNAXIChunk(_len,sz,bufS(sz));      
    }
    // Exponent scaling: replacing numbers like 1.3 with 13e-1.  '13' fits in a
    // byte and we scale the column by 0.1.  A set of numbers like
    // {1.2,23,0.34} then is normalized to always be represented with 2 digits
    // to the right: {1.20,23.00,0.34} and we scale by 100: {120,2300,34}.
    // This set fits in a 2-byte short.

    // We use exponent-scaling for bytes & shorts only; it's uncommon (and not
    // worth it) for larger numbers.  We need to get the exponents to be
    // uniform, so we scale up the largest lmax by the largest scale we need
    // and if that fits in a byte/short - then it's worth compressing.  Other
    // wise we just flip to a float or double representation.
    if( overflow || (fpoint && floatOverflow) || -35 > xmin || xmin > 35 )
      return chunkD();
    final long leRange = leRange(lemin,lemax);
    if( fpoint ) {
      if( (int)lemin == lemin && (int)lemax == lemax ) {
        if(leRange < 255) // Fits in scaled biased byte?
          return new C1SChunk( bufX(lemin,xmin,C1SChunk._OFF,0),lemin,PrettyPrint.pow10(xmin));
        if(leRange < 65535) { // we use signed 2B short, add -32k to the bias!
          long bias = 32767 + lemin;
          return new C2SChunk( bufX(bias,xmin,C2SChunk._OFF,1),bias,PrettyPrint.pow10(xmin));
        }
      }
      if(leRange < 4294967295l) {
        long bias = 2147483647l + lemin;
        return new C4SChunk( bufX(bias,xmin,C4SChunk._OFF,2),bias,PrettyPrint.pow10(xmin));
      }
      return chunkD();
    } // else an integer column

    // Compress column into a byte
    if(xmin == 0 &&  0<=lemin && lemax <= 255 && ((_naCnt + _catCnt)==0) )
      return new C1NChunk( bufX(0,0,C1NChunk._OFF,0));
    if( lemin < Integer.MIN_VALUE ) return new C8Chunk( bufX(0,0,0,3));
    if( leRange < 255 ) {    // Span fits in a byte?
      if(0 <= min && max < 255 ) // Span fits in an unbiased byte?
        return new C1Chunk( bufX(0,0,C1Chunk._OFF,0));
      return new C1SChunk( bufX(lemin,xmin,C1SChunk._OFF,0),lemin,PrettyPrint.pow10i(xmin));
    }

    // Compress column into a short
    if( leRange < 65535 ) {               // Span fits in a biased short?
      if( xmin == 0 && Short.MIN_VALUE < lemin && lemax <= Short.MAX_VALUE ) // Span fits in an unbiased short?
        return new C2Chunk( bufX(0,0,C2Chunk._OFF,1));
      long bias = (lemin-(Short.MIN_VALUE+1));
      return new C2SChunk( bufX(bias,xmin,C2SChunk._OFF,1),bias,PrettyPrint.pow10i(xmin));
    }
    // Compress column into ints
    if( Integer.MIN_VALUE < min && max <= Integer.MAX_VALUE )
      return new C4Chunk( bufX(0,0,0,2));
    return new C8Chunk( bufX(0,0,0,3));
  }

  private static long [] NAS = {C1Chunk._NA,C2Chunk._NA,C4Chunk._NA,C8Chunk._NA};

  // Compute a sparse integer buffer
  private byte[] bufS(final int valsz){
    int log = 0;
    while((1 << log) < valsz)++log;
    assert valsz == 0 || (1 << log) == valsz;
    final int ridsz = _len >= 65535?4:2;
    final int elmsz = ridsz + valsz;
    int off = CXIChunk._OFF;
    byte [] buf = MemoryManager.malloc1(off + _sparseLen *elmsz,true);
    for(int i = 0; i< _sparseLen; i++, off += elmsz ) {
      if(ridsz == 2)
        UnsafeUtils.set2(buf,off,(short)_id[i]);
      else
        UnsafeUtils.set4(buf,off,_id[i]);
      if(valsz == 0){
        assert _xs.get(i) == 0 && _ms.get(i) == 1;
        continue;
      }
      assert isNA2(i) || _xs.get(i) >= 0:"unexpected exponent " + _xs.get(i); // assert we have int or NA
      final long lval = isNA2(i) ? NAS[log] : _ms.get(i)*PrettyPrint.pow10i(_xs.get(i));
      switch(valsz){
        case 1:
          buf[off+ridsz] = (byte)lval;
          break;
        case 2:
          short sval = (short)lval;
          UnsafeUtils.set2(buf,off+ridsz,sval);
          break;
        case 4:
          int ival = (int)lval;
          UnsafeUtils.set4(buf, off + ridsz, ival);
          break;
        case 8:
          UnsafeUtils.set8(buf, off + ridsz, lval);
          break;
        default:
          throw H2O.fail();
      }
    }
    assert off==buf.length;
    return buf;
  }

  // Compute a sparse float buffer
  private byte[] bufD(final int valsz){
    int log = 0;
    while((1 << log) < valsz)++log;
    assert (1 << log) == valsz;
    final int ridsz = _len >= 65535?4:2;
    final int elmsz = ridsz + valsz;
    int off = CXDChunk._OFF;
    byte [] buf = MemoryManager.malloc1(off + _sparseLen *elmsz,true);
    for(int i = 0; i< _sparseLen; i++, off += elmsz ) {
      if(ridsz == 2)
        UnsafeUtils.set2(buf,off,(short)_id[i]);
      else
        UnsafeUtils.set4(buf,off,_id[i]);
      final double dval = _ds == null?isNA2(i)?Double.NaN:_ms.get(i)*PrettyPrint.pow10(_xs.get(i)):_ds[i];
      switch(valsz){
        case 4:
          UnsafeUtils.set4f(buf, off + ridsz, (float) dval);
          break;
        case 8:
          UnsafeUtils.set8d(buf, off + ridsz, dval);
          break;
        default:
          throw H2O.fail();
      }
    }
    assert off==buf.length;
    return buf;
  }
  // Compute a compressed integer buffer
  private byte[] bufX( long bias, int scale, int off, int log ) {
    byte[] bs = new byte[(_len <<log)+off];
    int j = 0;
    for( int i=0; i< _len; i++ ) {
      long le = -bias;
      if(_id == null || _id.length == 0 || (j < _id.length && _id[j] == i)){
        if( isNA2(j) ) {
          le = NAS[log];
        } else {
          int x = (_xs.get(j)==Integer.MIN_VALUE+1 ? 0 : _xs.get(j))-scale;
          le += x >= 0
              ? _ms.get(j)*PrettyPrint.pow10i( x)
              : _ms.get(j)/PrettyPrint.pow10i(-x);
        }
        ++j;
      }
      switch( log ) {
      case 0:          bs [i    +off] = (byte)le ; break;
      case 1: UnsafeUtils.set2(bs,(i<<1)+off,  (short)le); break;
      case 2: UnsafeUtils.set4(bs, (i << 2) + off, (int) le); break;
      case 3: UnsafeUtils.set8(bs, (i << 3) + off, le); break;
      default: throw H2O.fail();
      }
    }
    assert j == _sparseLen :"j = " + j + ", _sparseLen = " + _sparseLen;
    return bs;
  }

  // Compute a compressed double buffer
  private Chunk chunkD() {
    HashMap<Long,Byte> hs = new HashMap<>(CUDChunk.MAX_UNIQUES);
    Byte dummy = 0;
    final byte [] bs = MemoryManager.malloc1(_len *8,true);
    int j = 0;
    boolean fitsInUnique = true;
    for(int i = 0; i < _len; ++i){
      double d = 0;
      if(_id == null || _id.length == 0 || (j < _id.length && _id[j] == i)) {
        d = _ds != null?_ds[j]:(isNA2(j)|| isCategorical(j))?Double.NaN:_ms.get(j)*PrettyPrint.pow10(_xs.get(j));
        ++j;
      }
      if (fitsInUnique) {
        if (hs.size() < CUDChunk.MAX_UNIQUES) //still got space
          hs.put(Double.doubleToLongBits(d),dummy); //store doubles as longs to avoid NaN comparison issues during extraction
        else fitsInUnique = (hs.size() == CUDChunk.MAX_UNIQUES) && // full, but might not need more space because of repeats
                            hs.containsKey(Double.doubleToLongBits(d));
      }
      UnsafeUtils.set8d(bs, 8*i, d);
    }
    assert j == _sparseLen :"j = " + j + ", _len = " + _sparseLen;
    if (fitsInUnique && CUDChunk.computeByteSize(hs.size(), len()) < 0.8 * bs.length)
      return new CUDChunk(bs, hs, len());
    else
      return new C8DChunk(bs);
  }

  // Compute a compressed UUID buffer
  private Chunk chunkUUID() {
    final byte [] bs = MemoryManager.malloc1(_len *16,true);
    int j = 0;
    for( int i = 0; i < _len; ++i ) {
      long lo = 0, hi=0;
      if( _id == null || _id.length == 0 || (j < _id.length && _id[j] == i ) ) {
        lo = _ms.get(j);
        hi = Double.doubleToRawLongBits(_ds[j++]);
        if( _xs != null && _xs.get(j) == Integer.MAX_VALUE){// NA?
          lo = Long.MIN_VALUE; hi = 0;                  // Canonical NA value
        }
      }
      UnsafeUtils.set8(bs, 16*i  , lo);
      UnsafeUtils.set8(bs, 16 * i + 8, hi);
    }
    assert j == _sparseLen :"j = " + j + ", _sparselen = " + _sparseLen;
    return new C16Chunk(bs);
  }

  // Compute compressed boolean buffer
  private byte[] bufB(int bpv) {
    assert bpv == 1 || bpv == 2 : "Only bit vectors with/without NA are supported";
    final int off = CBSChunk._OFF;
    int clen  = off + CBSChunk.clen(_len, bpv);
    byte bs[] = new byte[clen];
    // Save the gap = number of unfilled bits and bpv value
    bs[0] = (byte) (((_len *bpv)&7)==0 ? 0 : (8-((_len *bpv)&7)));
    bs[1] = (byte) bpv;

    // Dense bitvector
    int  boff = 0;
    byte b    = 0;
    int  idx  = CBSChunk._OFF;
    int j = 0;
    for (int i=0; i< _len; i++) {
      byte val = 0;
      if(_id == null || (j < _id.length && _id[j] == i)) {
        assert bpv == 2 || !isNA2(j);
        val = (byte)(isNA2(j)?CBSChunk._NA:_ms.get(j));
        ++j;
      }
      if( bpv==1 )
        b = CBSChunk.write1b(b, val, boff);
      else
        b = CBSChunk.write2b(b, val, boff);
      boff += bpv;
      if (boff>8-bpv) { assert boff == 8; bs[idx] = b; boff = 0; b = 0; idx++; }
    }
    assert j == _sparseLen;
    assert bs[0] == (byte) (boff == 0 ? 0 : 8-boff):"b[0] = " + bs[0] + ", boff = " + boff + ", bpv = " + bpv;
    // Flush last byte
    if (boff>0) bs[idx] = b;
    return bs;
  }

  // Set & At on NewChunks are weird: only used after inflating some other
  // chunk.  At this point the NewChunk is full size, no more appends allowed,
  // and the xs exponent array should be only full of zeros.  Accesses must be
  // in-range and refer to the inflated values of the original Chunk.
  @Override boolean set_impl(int i, long l) {
    if( _ds   != null ) return set_impl(i,(double)l);
    if(_sparseLen != _len){ // sparse?
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0)i = idx;
      else cancel_sparse(); // for now don't bother setting the sparse value
    }
    _ms.set(i,l);
    _xs.set(i,0);
    _naCnt = -1;
    return true;
  }
  public boolean set_impl_long(int i, long l) {
    if( _ds   != null ) return set_impl(i,(double)l);
    if(_sparseLen != _len){ // sparse?
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0)i = idx;
      else cancel_sparse(); // for now don't bother setting the sparse value
    }
    _ms.set(i,l); _xs.set(i,0);
    _naCnt = -1;
    return true;
  }

  @Override public boolean set_impl(int i, double d) {
    if(_ds == null){
      if (_is == null) { //not a string
        assert _sparseLen == 0 || _ms != null;
        switch_to_doubles();
      } else {
        if (_is[i] == -1) return true; //nothing to do: already NA
        assert(Double.isNaN(d)) : "can only set strings to <NA>, nothing else";
        set_impl(i, null); //null encodes a missing string: <NA>
        return true;
      }
    }
    if(_sparseLen != _len){ // sparse?
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0)i = idx;
      else cancel_sparse(); // for now don't bother setting the sparse value
    }
    assert i < _sparseLen;
    _ds[i] = d;
    _naCnt = -1;
    return true;
  }
  @Override boolean set_impl(int i, float f) {  return set_impl(i,(double)f); }

  @Override boolean set_impl(int i, String str) {
    if(_is == null && _len > 0) {
      assert _sparseLen == 0;
      alloc_str_indices(_len);
      Arrays.fill(_is,-1);
    }
    if(_sparseLen != _len){ // sparse?
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0)i = idx;
      else cancel_sparse(); // for now don't bother setting the sparse value
    }
    _is[i] = _sslen;
    append_ss(str);
    return true;
  }

  protected final boolean setNA_impl2(int i) {
    if( isNA2(i) ) return true;
    if( _ms != null ) {
      _ms.setNA(i);
      _xs.set(i,0);
    }
    if( _ds != null ) { _ds[i] = Double.NaN; }
    if (_is != null) { _is[i] = -1; }
    _naCnt = -1;
    return true;
  }
  @Override boolean setNA_impl(int i) {
    if( isNA_impl(i) ) return true;
    if(_sparseLen != _len){
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0) i = idx;
      else cancel_sparse(); // todo - do not necessarily cancel sparse here
    }
    return setNA_impl2(i);
  }
  
  protected final long at8_impl2(int i) {
    if(isNA2(i))throw new RuntimeException("Attempting to access NA as integer value.");
    if( _ms == null ) return (long)_ds[i];
    return _ms.get(i)*PrettyPrint.pow10i(_xs.get(i));
  }
  
  @Override public long at8_impl( int i ) {
    if( _len != _sparseLen) {
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0) i = idx;
      else {
        if (_sparseNA) throw new RuntimeException("Attempting to access NA as integer value.");
        return 0;
      }
    }
    return at8_impl2(i);
  }
  @Override public double atd_impl( int i ) {
    if( _len != _sparseLen) {
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0) i = idx;
      else return sparseNA() ? Double.NaN : 0;
    }
    if (isNA2(i)) return Double.NaN;
    // if exponent is Integer.MIN_VALUE (for missing value) or >=0, then go the integer path (at8_impl)
    // negative exponents need to be handled right here
    if( _ds == null ) return _xs.get(i) >= 0 ? at8_impl2(i) : _ms.get(i)*Math.pow(10,_xs.get(i));
    assert _xs==null; 
    return _ds[i];
  }
  @Override protected long at16l_impl(int idx) {
    if(_ms.get(idx) == C16Chunk._LO_NA) throw new RuntimeException("Attempting to access NA as integer value.");
    return _ms.get(idx);
  }
  @Override protected long at16h_impl(int idx) {
    long hi = Double.doubleToRawLongBits(_ds[idx]);
    if(hi == C16Chunk._HI_NA) throw new RuntimeException("Attempting to access NA as integer value.");
    return hi;
  }
  @Override public boolean isNA_impl( int i ) {
    if (_len != _sparseLen) {
      int idx = Arrays.binarySearch(_id, 0, _sparseLen, i);
      if (idx >= 0) i = idx;
      else return sparseNA();
    }
    return !sparseNA() && isNA2(i);
  }
  @Override public BufferedString atStr_impl( BufferedString bStr, int i ) {
    if( _sparseLen != _len ) {
      int idx = Arrays.binarySearch(_id,0, _sparseLen,i);
      if(idx >= 0) i = idx;
      else return null;
    }

    if( _is[i] == CStrChunk.NA ) return null;

    int len = 0;
    while( _ss[_is[i] + len] != 0 ) len++;
    return bStr.set(_ss, _is[i], len);
  }
  @Override protected final void initFromBytes () {throw H2O.fail();}
  public static AutoBuffer write_impl(NewChunk nc,AutoBuffer bb) { throw H2O.fail(); }
  @Override public NewChunk inflate_impl(NewChunk nc) { throw H2O.fail(); }
  @Override public String toString() { return "NewChunk._sparseLen="+ _sparseLen; }

  // We have to explicitly override cidx implementation since we hide _cidx field with new version
  @Override
  public int cidx() {
    return _cidx;
  }
}
