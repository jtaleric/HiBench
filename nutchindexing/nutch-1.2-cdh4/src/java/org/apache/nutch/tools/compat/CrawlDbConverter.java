/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.tools.compat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapFileOutputFormat;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.CrawlDb;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TimingUtil;

/**
 * This tool converts CrawlDb created in old &lt;UTF8, CrawlDatum&gt; format
 * (Nutch versions < 0.9.0) to the new &lt;Text, CrawlDatum&gt; format.
 * Optionally {@link org.apache.nutch.crawl.CrawlDatum#metaData} can be converted
 * too from using UTF8 keys to using Text keys.
 * 
 * @author Andrzej Bialecki
 */
public class CrawlDbConverter extends Configured implements Tool,
    Mapper<WritableComparable, CrawlDatum, Text, CrawlDatum> {
  private static final Log LOG = LogFactory.getLog(CrawlDbConverter.class);
  
  private static final String CONVERT_META_KEY = "db.converter.with.metadata";

  private boolean withMetadata;
  private Text newKey;
  
  public void configure(JobConf job) {
    setConf(job);
    withMetadata = job.getBoolean(CONVERT_META_KEY, false);
    newKey = new Text();
  }

  public void map(WritableComparable key, CrawlDatum value,
      OutputCollector<Text, CrawlDatum> output,
      Reporter reporter) throws IOException {
    newKey.set(key.toString());
    if (withMetadata) {
      CrawlDatum datum = (CrawlDatum)value;
      MapWritable meta = datum.getMetaData();
      if (meta.size() > 0) {
        MapWritable newMeta = new MapWritable();
        Iterator it = meta.keySet().iterator();
        while (it.hasNext()) {
          WritableComparable k = (WritableComparable)it.next();
          Writable v = meta.get(k);
          if (k instanceof UTF8) {
            Text t = new Text(k.toString());
            k = t;
          }
          newMeta.put(k, v);
        }
        datum.setMetaData(newMeta);
      }
    }
    output.collect(newKey, value);
  }

  public void close() throws IOException {
  }

  /**
   * @param args
   */
  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(NutchConfiguration.create(), new CrawlDbConverter(), args);
    System.exit(res);
  }

  public int run(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Usage: CrawlDbConverter <oldDb> <newDb> [-withMetadata]");
      System.err.println("\toldDb\tname of the crawldb that uses UTF8 class.");
      System.err.println("\tnewDb\tname of the output crawldb that will use Text class.");
      System.err.println("\twithMetadata\tconvert also all metadata keys that use UTF8 to Text.");
      return -1;
    }
    JobConf job = new NutchJob(getConf());
    FileSystem fs = FileSystem.get(getConf());
    Path oldDb = new Path(args[0], CrawlDb.CURRENT_NAME);
    Path newDb =
      new Path(oldDb,
               Integer.toString(new Random().nextInt(Integer.MAX_VALUE)));
    if (!fs.exists(oldDb)) {
      LOG.fatal("Old db doesn't exist in '" + args[0] + "'");
      return -1;
    }
    boolean withMetadata = false;
    if (args.length > 2 && args[2].equalsIgnoreCase("-withMetadata"))
      withMetadata = true;
    
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();
    LOG.info("CrawlDbConverter: starting at " + sdf.format(start));

    job.setBoolean(CONVERT_META_KEY, withMetadata);
    FileInputFormat.addInputPath(job, oldDb);
    job.setInputFormat(SequenceFileInputFormat.class);
    job.setMapperClass(CrawlDbConverter.class);
    job.setOutputFormat(MapFileOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(CrawlDatum.class);
    FileOutputFormat.setOutputPath(job, newDb);
    try {
      JobClient.runJob(job);
      CrawlDb.install(job, new Path(args[1]));
    } catch (Exception e) {
      LOG.fatal("Error: " + StringUtils.stringifyException(e));
      return -1;
    }

    long end = System.currentTimeMillis();
    LOG.info("CrawlDb scanner: finished at " + sdf.format(end) + ", elapsed: " + TimingUtil.elapsedTime(start, end));
    return 0;
  }
}
