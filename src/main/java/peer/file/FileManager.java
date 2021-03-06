package peer.file;

import peer.channel.*;
import peer.message.*;
import peer.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import javax.xml.bind.DatatypeConverter;
import java.util.*;

/**
* A file manager to handle file transfers
*/
public class FileManager {

  /**
  * Constructor
  */
  public FileManager() {

  }


  /**
  * Returns the ID of a given file
  *
  * @param filepath Path to the file
  *
  * @return File ID
  */
  public String getFileId(String filepath) {

    Path path = Paths.get(filepath);

    try {

      // get file attrs
      BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);

      // generate a string containing attrs
      String rawId = filepath + attrs.size() + attrs.creationTime() + attrs.lastModifiedTime();

      // convert string to bitstring
      String bitId = stringToBitstring(rawId);

      // hash bitstring
      String hashedId = hashString(bitId);

      return hashedId;
    }
    catch (Exception e) {
      System.out.println("FileManager: Error creating file id: " + e);
      return null;
    }

  }


  /**
  * Converts a given string into a bitstring
  *
  * @param str String to convert
  *
  * @return Bitstring
  */
  public String stringToBitstring(String str) {

    // get bytes from string
    byte[] bytes = str.getBytes();

    // init output
    String bitstring = "";

    // loop each byte and turn them into bits
    for (byte b: bytes) {
      bitstring += Integer.toBinaryString(b);
    }

    return bitstring;
  }


  /**
  * Hashes a string by applying SHA256
  *
  * @param str String to hash
  *
  * @return Hashed string
  */
  public String hashString(String str) {

    try {

      // create message digest sha256
      MessageDigest md = MessageDigest.getInstance("SHA-256");

      // get hashed byte array
      byte[] hashed = md.digest(str.getBytes());

      // return hashed string
      return DatatypeConverter.printHexBinary(hashed);
    }
    catch (Exception e) {

      System.out.println("FileManager: Error hashing string: " + e);
      return null;
    }
  }


  /**
  * Backs up the given file
  * by splitting it into 64Kb chunks
  * and asking other peers to store them
  *
  * @param filepath Path to the file to backup
  * @param repDeg {@link message.MessageHeader#repDeg}
  */
  public void backup(String filepath, String repDeg) {

    File file = new File(filepath);

    long filesize = file.length();

    // init array
    byte[] chunk = new byte[Message.CHUNK_SIZE];

    try {

      FileInputStream fis = new FileInputStream(file);

      String fileId = getFileId(filepath);

      int chunkNo = 0;
      int nread = 0;

      // read file into chunks
      while ((nread = fis.read(chunk)) > 0) {

        // ignore trailing garbage left by read
        byte[] body = new byte[nread];

        // TODO make sure this is correct. read was leaving last bytes with garbage?
        System.arraycopy(chunk, 0, body, 0, nread);

        PutChunkMessage msg = new PutChunkMessage(fileId, Integer.toString(chunkNo), repDeg, body);

        // add this message to waiting "queue"
        synchronized (ControlChannelListener.waitingConfirmation) {
          ControlChannelListener.waitingConfirmation.add(msg);
        }

        // send message to MDB channel
        msg.send();

        // prepare next ite
        chunkNo++;
      }

      // If the file size is a multiple of the chunk size,
      // the last chunk has size 0
      if (filesize % Message.CHUNK_SIZE == 0) {

        // get message to send to multicast channel
        PutChunkMessage lastmsg = new PutChunkMessage(fileId, Integer.toString(chunkNo), repDeg, new byte[0]);

        // send message
        lastmsg.send();
      }

      fis.close();

    }
    catch (Exception e) {
      System.out.println("FileManager: Error opening/reading file " + filepath + ": " + e);
    }

  }


  /** TODO criar send() em todos os tipos de mensagem!
  * Restores a file by asking for all of its chunks
  *
  * @param filepath File to restore
  */
  public void restore(String filepath) {

    File file = new File(filepath);

    long filesize = file.length();

    String fileId = getFileId(filepath);

    int nchunks = (int) Math.ceil(file.length() / (double)Message.CHUNK_SIZE);

    System.out.println("debug nchunks: " + nchunks);

    for (int i = 0; i < nchunks; i++) {

      // ask for the chunk
      GetChunkMessage msg = new GetChunkMessage(fileId, Integer.toString(i));

      // add this message to waiting "queue"
      synchronized (RestoreChannelListener.waitingConfirmation) {
        RestoreChannelListener.waitingConfirmation.add(msg);
      }

      // send message to MC channel
      msg.send();
    }
  }


  /** TODO mapeamento fileId filepath hardcoded teste.txt
  * Builds a file out of chunks
  *
  * @param msg Message cointaining the chunk
  */
  public void build(Message msg) {

    try {
      FileOutputStream output = new FileOutputStream("teste.txt", true);
      output.write(msg.getBody());
      output.close();
    }
    catch (Exception e) {
      System.out.println("File Manager: Error building file: " + e);
    }
  }


  /**
  * Stores the chunk cointained in the msg
  *
  * @param msg Message containing the chunk to store
  */
  public void store(Message msg) {

    String filepath = msg.getChunkPath();

    // create a byte[] to store only the actual content of the chunk
    byte[] content = new byte[msg.getBodyLength()];

    System.arraycopy(msg.getBody(), 0, content, 0, content.length);

    try {
      FileOutputStream out = new FileOutputStream(filepath);
      out.write(content);
      out.close();
    }
    catch (Exception e) {
      System.out.println("FileManager: Error storing chunk " + filepath + ": " + e);
    }
  }

  /**
  * Adds an entry to the log file
  *
  * @param sender {@link message.MessageHeader#senderId}
  * @param file {@link message.MessageHeader#fileId}
  * @param chunk {@link message.MessageHeader#chunkNo}
  * @param desiredRep {@link message.MessageHeader#repDeg}
  * @param actualRep {@link message.PutChunkMessage#actualRepDeg}
  */
  public void addChunkInfoToFile(String sender, String file, String chunk, String desiredRep, String actualRep) {

    try {

      //ler info que ja la esta
      ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(Peer.CHUNKS_PATH + "/currentInfo.info"));
      String[] currentInfo = (String[])inputStream.readObject();

      //System.out.println("de currentInfo.txt: "+Arrays.toString(currentInfo));

      //adicionar info
      ArrayList<String> currentInfoList = new ArrayList<String>(Arrays.asList(currentInfo));
      String newInfo = sender+"-"+file+"-"+chunk+"-"+desiredRep+"-"+actualRep;
      currentInfoList.add(newInfo);

      //voltar a guardar no ficheiro
      currentInfo = currentInfoList.toArray(new String[currentInfoList.size()]);
      ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(Peer.CHUNKS_PATH + "/currentInfo.info"));
      outputStream.writeObject(currentInfo);

      //System.out.println("para currentInfo.txt: "+Arrays.toString(currentInfo));

      inputStream.close();
    }
    catch(Exception e) {
        System.out.println("FileManager > addChunkInfoToFile: " + e);
    }
  }

  public void writeMaps(){

    List<String> chunkMapList = new LinkedList<String>();


    try{
      ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(Peer.CHUNKS_PATH + "/chunkMap.info"));


      File dir = new File(Peer.CHUNKS_PATH);

      File[] matches = dir.listFiles(new FilenameFilter()
      {
        public boolean accept(File dir, String name)
        {
          return name.endsWith(".chk");
        }
      });

      for(int i = 0; i < matches.length; i++){
        String chunkName = matches[i].getName();
        chunkMapList.add(chunkName);
      }

      String[] chunkMapArray = chunkMapList.stream().toArray(String[]::new);
      outputStream.writeObject(chunkMapArray);

    }
    catch(Exception e){
      System.out.println("FileManager > writeMaps: " +e);
    }



  }

  public String[] readMaps () {

    String[] currentMaps = null;

    try{
      ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(Peer.CHUNKS_PATH + "/chunkMap.info"));

      currentMaps = (String[])inputStream.readObject();
      //  System.out.println(Arrays.toString(currentInfo));

    }
    catch(Exception e){
      System.out.println("FileManager > readMaps: " +e);
    }

    return currentMaps;

  }

}
