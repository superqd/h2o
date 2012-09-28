package hex.rf;

import hex.rf.Data.Row;
import hex.rf.Tree.StatType;
import hex.rf.Utils.Counter;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import water.AppendKey;
import water.Arguments;
import water.DKV;
import water.H2O;
import water.Key;
import water.UDPRebooted;
import water.ValueArray;
import water.util.KeyUtil;

/**
 * A RandomForest can be used for growing or validation. The former starts with a known target number of trees,
 * the latter is incrementally populated with trees as they are built.
 * Validation and error reporting is not supported when growing a forest.
 */
public class RandomForest {
  final Tree[] _trees;          // The trees that got built
  final Data _data;             // The data to train on.
  private int _features = -1;   // features to check at each split

  public RandomForest( DRF drf, Data data, int ntrees, int maxTreeDepth, double minErrorRate, StatType stat, boolean singlethreaded ) {

    // Build N trees via the Random Forest algorithm.
    _data = data;
    _trees = new Tree[ntrees];
    long start = System.currentTimeMillis();
    try {
      // build one tree at a time, and forget it
      for( int i=0; i<ntrees; i++ ) {
        H2O.FJP_NORM.submit(_trees[i] = new Tree(_data.clone(),maxTreeDepth,minErrorRate,stat,features(), i + data.seed()));
        _trees[i].get();        // Block for a tree
        new AppendKey(_trees[i].toKey()).fork(drf._treeskey);        // Atomic-append to the list of trees
        long now = System.currentTimeMillis();
        System.out.println("Tree "+i+" ready after "+(now-start)+" msec");
        _trees[i]= null; // Forget it.
      }
    } catch( InterruptedException e ) {
      // Interrupted after partial build?
    } catch( ExecutionException e ) {
    }
  }

  public static class OptArgs extends Arguments.Opt {
	String file = "smalldata/poker/poker-hand-testing.data";
	String rawKey;
	String parsdedKey;
	String validationFile = null;
	String h2oArgs = "";
	int ntrees = 10;
	int depth = -1;
	double cutRate = 0;
	String statType = "entropy";
	int seed = 42;
	boolean singlethreaded;
  }

  static final OptArgs ARGS = new OptArgs();

  public int features() { return _features== -1 ? (int)Math.sqrt(_data.columns()) : _features; }


  public static void main(String[] args) throws Exception {
    Arguments arguments = new Arguments(args);
    arguments.extract(ARGS);
    if(ARGS.h2oArgs.startsWith("\"") && ARGS.h2oArgs.endsWith("\""))
      ARGS.h2oArgs = ARGS.h2oArgs.substring(1, ARGS.h2oArgs.length()-1);
    ARGS.h2oArgs = ARGS.h2oArgs.trim();
    String [] h2oArgs = ARGS.h2oArgs.split("[ \t]+");
    System.out.println("H2O args = " + Arrays.toString(h2oArgs));
    H2O.main(h2oArgs);
    System.out.println(ARGS.file);
    Thread.sleep(100);
    ValueArray va;
    // get the input data
    if(ARGS.parsdedKey != null) // data already parsed
      va = (ValueArray)DKV.get(Key.make(ARGS.parsdedKey));
    else if(ARGS.rawKey != null) // data loaded in K/V, not parsed yet
      va = KeyUtil.parse_test_key(Key.make(ARGS.rawKey),Key.make(KeyUtil.getHexKeyFromRawKey(ARGS.rawKey)));
    else { // data outside of H2O, load and parse
      File f = new File(ARGS.file);
      System.out.println("[RF] Loading file " + f);
      Key fk = KeyUtil.load_test_file(f);
      va = KeyUtil.parse_test_key(fk,Key.make(KeyUtil.getHexKeyFromFile(f)));
      DKV.remove(fk);
    }
    int ntrees = ARGS.ntrees;
    if(ntrees == 0) {
      System.out.println("Nothing to do as ntrees == 0");
      UDPRebooted.global_kill();
      return;
    }
    DRF.sample = (ARGS.validationFile == null || ARGS.validationFile.isEmpty());
    DRF.forceNoSample = (ARGS.validationFile != null && !ARGS.validationFile.isEmpty());
    StatType st = ARGS.statType.equals("gini") ? StatType.GINI : StatType.ENTROPY;
    Utils.pln("[RF] Starting RF.");
    long t1 = System.currentTimeMillis();
    DRF drf = DRF.web_main(va, ARGS.ntrees, ARGS.depth, ARGS.cutRate, st, ARGS.seed, ARGS.singlethreaded);
    Key[] tkeys = null;
    while(tkeys == null || tkeys.length!=ntrees) tkeys = drf._treeskey.flatten();
    long t2 = System.currentTimeMillis();
    assert tkeys.length == ntrees;
    if(ARGS.validationFile != null && !ARGS.validationFile.isEmpty()){ // validate n the suplied file
      DRF.forceNoSample = true;
      Key valKey = KeyUtil.load_test_file(ARGS.validationFile);
      ValueArray valAry = KeyUtil.parse_test_key(valKey);
      Key[] keys = new Key[(int)valAry.chunks()];
      for( int i=0; i<keys.length; i++ )
        keys[i] = valAry.chunk_get(i);
      Data valData = Data.make(drf.extractData(valAry._key, keys));
      new RFValidator( tkeys, valData, valAry, drf._rf.features() ).report();
    } else
      new RFValidator( tkeys, drf._validation, va, drf._rf.features() ).report();
    System.out.println("Random forest finished in: " + (t2 - t1) + " ms");
    UDPRebooted.global_kill();
  }

  // ---
  static class RFValidator {
    private int _lastTreeValidated;
    private long _errors = -1;
    private final int[][] _scores;
    private final int[][] _confusion;
    private final byte _tbits[][];    // Array of compressed trees
    private final Data _data;
    private final ValueArray _raw;
    private final int _features;
    RFValidator( Key[] treekeys, Data data, ValueArray va, int features ) {
      _data = data;
      _scores    = new int[data.rows()   ][data.classes()];
      _confusion = new int[data.classes()][data.classes()];
      _raw = va;
      _features = features;
      // Pre-load all the compressed trees
      _tbits = new byte[treekeys.length][];
      for( int i=0; i<treekeys.length; i++ )
        _tbits[i] = DKV.get(treekeys[i]).get();
    }

    /** Incrementally validate new trees in the forest. */
    private void validate() {
      _errors = 0; int i = 0;
      final int rowsize = _raw.row_size();
      final int rpc = (int)(ValueArray.chunk_size()/rowsize); // Rows per chunk

      // Track current chunk we are dealing with
      long chk = 0;              // 1meg data chunk#
      byte dbits[] = DKV.get(_raw.make_chunkkey(0)).get();
      int numrows0 = dbits.length/rowsize; // Rows in the 1meg chunk 'chk'
      Random random = new Random(_data.seed());
      for( Row r : _data ) {
        final long rchk = _raw.chunk_for_row(r.index,rpc);
        if( rchk != chk ) {     // Changing chunks?
          chk = rchk;           // Switching to chunk
          dbits = DKV.get(_raw.make_chunkkey(rchk<<ValueArray.LOG_CHK)).get();
          numrows0 = dbits.length/rowsize; // Rows in the 1meg chunk 'chk'
        }
        final int ridx = _raw.row_in_chunk(r.index,rpc,rchk);
        assert 0 <= ridx && ridx < numrows0;

        int realClass = r.classOf();
        int[] predClasses = _scores[i];
        for( int j = _lastTreeValidated; j< _tbits.length; j++)
          predClasses[Tree.classify(_tbits[j],_raw,dbits,ridx,rowsize)]++;
        int predClass = Utils.maxIndex(predClasses, random);
        if (realClass != predClass)  ++_errors;
        _confusion[realClass][predClass]++;
        ++i;
      }
      _lastTreeValidated = _tbits.length-1;
    }

    /** The number of misclassifications observed to date. */
    private long errors() {
      if(_errors==-1) validate();
      return _errors;
  }


    private String pad(String s, int l) {
      String p="";
      for (int i=0;i < l - s.length(); i++) p+= " ";
      return " "+p+s;
    }

    private String confusionMatrix() {
      final int K = _data.classes()+1;
      double[] e2c = new double[_data.classes()];
      for(int i=0;i<_data.classes();i++) {
        int err = -_confusion[i][i];;
        for(int j=0;j<_data.classes();j++) err+=_confusion[i][j];
        e2c[i]= Math.round((err/(double)(err+_confusion[i][i]) ) * 100) / (double) 100  ;
      }
      String [][] cms = new String[K][K+1];
      cms[0][0] = "";
      for (int i=1;i<K;i++) cms[0][i] = ""+ (i-1); //cn[i-1];
      cms[0][K]= "err/class";
      for (int j=1;j<K;j++) cms[j][0] = ""+ (j-1); //cn[j-1];
      for (int j=1;j<K;j++) cms[j][K] = ""+ e2c[j-1];
      for (int i=1;i<K;i++)
        for (int j=1;j<K;j++) cms[j][i] = ""+_confusion[j-1][i-1];
      int maxlen = 0;
      for (int i=0;i<K;i++)
        for (int j=0;j<K+1;j++) maxlen = Math.max(maxlen, cms[i][j].length());
      for (int i=0;i<K;i++)
        for (int j=0;j<K+1;j++) cms[i][j] = pad(cms[i][j],maxlen);
      String s = "";
      for (int i=0;i<K;i++) {
        for (int j=0;j<K+1;j++) s += cms[i][j];
        s+="\n";
      }
      return s;
    }


    public final void report() {
      validate();
      Counter td = new Counter(), tl = new Counter();
      for( byte[]tbits : _tbits) {
        long dl = Tree.depth_leaves(tbits);
        td.add((int)(dl>>32)); tl.add((int)dl); }
      double err = errors()/(double) _data.rows();
      String s =
        "              Type of random forest: classification\n" +
        "                    Number of trees: "+ _tbits.length +"\n"+
        "No of variables tried at each split: " + _features +"\n"+
        "             Estimate of error rate: " + Math.round(err *10000)/100 + "%  ("+err+")\n"+
        "                   Confusion matrix:\n" + confusionMatrix()+ "\n"+
        "          Avg tree depth (min, max): " + td +"\n" +
        "         Avg tree leaves (min, max): " + tl +"\n" +
        "                Validated on (rows): " + _data.rows() ;
      System.out.println(s);
    }
  }
}

