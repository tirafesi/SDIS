package peer.channel;

import peer.*;
import peer.message.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;

/**
* Peer thread to listen to the multicast control channel (MC)
*/
public class ControlChannelListener extends ChannelListener {

  /** {@link ChannelListener#channelName} */
  public static final String CHANNEL_NAME = "(MC) Control Channel";
  /** {@link ChannelMessenger#messengerName} */
  public static final String MESSENGER_NAME = CHANNEL_NAME + " Messenger";
  /** {@link ChannelListener#channelPort} */
  public static final int CHANNEL_PORT = Peer.MC_PORT;
  /** {@link ChannelListener#channelAddress} */
  public static final String CHANNEL_ADDRESS = Peer.MC_ADDRESS;
  /** {@link ChannelListener#bufferSize} */
  public static final int BUFFER_SIZE = Peer.BUFFER_SIZE;
  /** A synchronized arraylist holding messages waiting for STORED confirmation */
  public static ArrayList<PutChunkMessage> waitingConfirmation = new ArrayList<PutChunkMessage>(Collections.synchronizedList(new ArrayList<PutChunkMessage>()));

  /**
  * Constructor
  */
  public ControlChannelListener() {
    super(CHANNEL_NAME, CHANNEL_PORT, CHANNEL_ADDRESS, BUFFER_SIZE);
  }


  /**
  * Searches {@link #waitingConfirmation} to see if the received STORED message is a match
  *
  * @param received Message to search
  *
  * @return Index of found match. -1 otherwise
  */
  private int searchWaitingConfirmation(Message received) {

    for (int i = 0; i < waitingConfirmation.size(); i++) {

      PutChunkMessage msg = waitingConfirmation.get(i);

      if (msg.getFileId().equals(received.getFileId()) && msg.getChunkNo().equals(received.getChunkNo())) {
        return i;
      }
    }

    return -1;
  }

  @Override
  protected void handler(Message received) {
System.out.println("RECEIVED: " + received.getType());

    switch (received.getType()) {

     case "STORED":{
        synchronized (waitingConfirmation) {

          int i;

          // check if this peer is interested in this store
          if ((i = searchWaitingConfirmation(received)) >= 0) {

            PutChunkMessage msg = waitingConfirmation.get(i);


            // add sender to history
            if (msg.addSaver(received.getSenderId())) {

              // add one to rep deg
              msg.addActualRepDeg();
            }

            // if time window for stored is over
            if (!msg.getWaiting()) {
              // check if repDeg was achieved and act accordingly
              msg.checkRepDeg();
            }
        }
        }
        break;
        }

     case "GETCHUNK":{ // iniciator peer manda msg getchunk para MC

          int delay = 0;

          try{
            String fileName = received.getFileId() + "-" + received.getChunkNo();
            int chunkIndex = getChunkIndex(fileName);

            if(chunkIndex > -1){
              //chunk presente no peer. ir buscar body:

              String filePath = received.getChunkPath();
              File chunk = new File(filePath);

              System.out.println(filePath);

              if(chunk.exists()){ //so para ter a certeza

                byte[] body = Files.readAllBytes(Paths.get(filePath));

                ChunkMessage outmsg = new ChunkMessage(received.getFileId(), received.getChunkNo(), body);


                // generate a random delay [1-400]ms
                delay = ThreadLocalRandom.current().nextInt(1, 401);
                // ask a messenger to deliver the message
                RestoreChannelListener.sendMessage(outmsg);
              }

            }
            else{ //peer nao tem o body
              break;
            }

          }
          catch(Exception e){
            System.out.println("RestoreChannelListener for CHUNK: " +e);
          }
          break;
        }

     case "DELETE":{

          try{

              File dir = new File(Peer.CHUNKS_PATH);

              File[] matches = dir.listFiles(new FilenameFilter()
              {
                public boolean accept(File dir, String name)
                {
                   return name.startsWith(received.getFileId()) && name.endsWith(".chk");
                }
              });

                 for(int i = 0; i < matches.length; i++)
                  {
                      matches[i].delete();
                  }

                      removeFromPeerChunks(matches);

                 removeFromPeerChunks(matches);

          }
          catch(Exception e){
              System.out.println("ControlChannelListener for CHUNK: " +e);
          }
          break;

      }

     case "REMOVED":{

             updateLocalChunkCount(received.getFileId());

         }

     default:
     break;
     }
}

  /**
  * Create a Messenger to send a message to this channel
  *
  * @param msg {@link ChannelMessenger#message}
  * @param delay {@link ChannelMessenger#delay}
  */
  public static void sendMessage(Message msg, int delay) {
    new Thread(new ChannelMessenger(MESSENGER_NAME, CHANNEL_PORT, CHANNEL_ADDRESS, BUFFER_SIZE, msg, delay)).start();
  }

  public void removeFromPeerChunks(File[] chunkFiles){ // eliminar todas as ocurrencias de chunks com um certo fileid do ficheiro com info dos chunks guardados

      try{
        /*
          //cena falsa para por chunks para poder ler
          String[] chunks = {"ER23R5-1","A1B2C3-24","A1B2C3-2","6THF76-143","KL999H-23","JUSNWW-2","YH65SD-4","LA89DH-8","7UUUYU-3"};
          outputStream.writeObject(chunks);
          //fim da cena falsa
          */

          //ler chunkIds do peer

          ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(Peer.CHUNKS_PATH + "/currentInfo.info"));
          String[] chunksNosInThisPeer = (String[])inputStream.readObject();

          //procurar chunkNo nos chunkNos deste peer
          List<String> chunkList = new LinkedList<String>(Arrays.asList(chunksNosInThisPeer));
          //int chunkIndex = chunkList.indexOf(fileIdChunkNo);


          for(int i = 0; i < chunkFiles.length; i++)
          {
              String noExtension = chunkFiles[i].getName().replace(".chk", "");
              chunkList.remove(noExtension);
          }

          // Integer[] newChunks = chunkList.toArray(new Arrays[chunkList.size()]);

          ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(Peer.CHUNKS_PATH +"/currentInfo.info"));
          String[] newChunks = chunkList.stream().toArray(String[]::new);

          outputStream.writeObject(newChunks);

      }
      catch(Exception e){
          System.out.println("BackupChannelListener for DELETE: " +e);
      }

  }

  public void updateLocalChunkCount(String fileID){


      try{
        /*
          //cena falsa para por chunks para poder ler
          String[] senderId_fileId_chunkNo_intendedRepDeg_actualRepdeg = {"2-ER23R5-2-3-3","1-A1B2C3-24-2-2","1-A1B2C3--2-2-2","2-6THF76-43-1-1","4-KL999H-785-3-3","6-JUSNWW-245-2-2","5-YH65SD-1-4-3","5-LA89DH-23-1-1","2-7UUUYU-34-3-3"};
          ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream("testing/currentInfo.txt"));
          outputStream.writeObject(senderId_fileId_chunkNo_intendedRepDeg_actualRepdeg);
          //fim da cena falsa
          */

          //ler chunkIds do peer
          ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(Peer.CHUNKS_PATH + "/currentInfo.info"));
          String[] currentInfo = (String[])inputStream.readObject();

          List<String> infoList = new LinkedList<String>(Arrays.asList(currentInfo));

          for(int i = 0; i < infoList.size(); i++)
          {
              String[] tmpInfo = infoList.get(i).split("-");

              //System.out.println("tmp senderID: "+tmpInfo[0]+" tmp fileId: "+tmpInfo[1]+" tmp chunkno: "+tmpInfo[2]+" tmp des rep: "+tmpInfo[3]+" tmp act rep: "+tmpInfo[4]+ " / fileID: "+ fileID);

              if(fileID.equals( tmpInfo[1] )){

                  String infoChunkNo = tmpInfo[2];
                  int desiredRep = Integer.parseInt(tmpInfo[3]);
                  int currRep = Integer.parseInt(tmpInfo[4]);
                  currRep--;

                  //atualizar o array de info de chunks
                  tmpInfo[4] = "" + currRep;
                  String newChunkInfo = tmpInfo[0]+"-"+tmpInfo[1]+"-"+tmpInfo[2]+"-"+tmpInfo[3]+"-"+tmpInfo[4];
                  infoList.set(i, newChunkInfo);
                  //eliminar este .chk
                 // deleteChunkFile(fileID, infoChunkNo);
                  //eliminar da lista de chunks guardados
                 // tmpInfo.remove(i);

                 if(currRep < desiredRep){

                     //ler body
                    byte[] body = getChunkBody(fileID, infoChunkNo);

                      //send PUTCHUNK
                      PutChunkMessage outmsg = new PutChunkMessage(tmpInfo[1], tmpInfo[2], tmpInfo[3], body);

                      // change id to match original sender, not this peer
                      outmsg.setSenderId(tmpInfo[0]);

                      // generate a random delay [1-400]ms
                      int delay = ThreadLocalRandom.current().nextInt(1, 401);
                      // ask a messenger to deliver the message

                     // esta a dar excecao porque??
                     // ControlChannelListener.sendMessage(outmsg, delay);
                  }
              }
          }

          ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(Peer.CHUNKS_PATH +"/currentInfo.info"));

          String[] newInfo = infoList.stream().toArray(String[]::new);

          outputStream.writeObject(newInfo);

          //System.out.println(Arrays.toString(newInfo));


      }
      catch(Exception e){
          System.out.println("ControlChannelListener for REMOVED > updateLocalChunkCount: " +e);
      }



  }

  public void deleteChunkFile(String fileId, String chunkNo){ //delete um ficheiro .chk

      try{
          String fileName = fileId + "-" + chunkNo;
          String filePath = "chunks/" + fileName + ".chk";
          File chunk = new File(filePath);

          if(chunk.exists()){
              chunk.delete();
          }

      }
      catch(Exception e){
          System.out.println("ControlChannelListener > deleteChunkFile: " +e);
      }

  }

  public byte[] getChunkBody(String fileId, String chunkNo){
      byte[] ret = null;

      try{
          String fileName = fileId + "-" + chunkNo;
          String filePath = "chunks/" + fileName + ".chk";
          File chunk = new File(filePath);

          if(chunk.exists()){
              ret = Files.readAllBytes(Paths.get(filePath));
          }

      }
      catch(Exception e){
          System.out.println("ControlChannelListener > deleteChunkFile: " +e);
      }
      return ret;
  }

  /**
  * Look for chunkNo in list of chunkNos stored in this peer
  *
  * @param chunkNo chunk number to look for
  *
  * @return index of chunkNo in array of chunkNos or -1 if not found
  */
  public int getChunkIndex(String fileName){
      int chunkIndex = -1;

      try{
        /*
          //cena falsa para por chunks para poder ler
          String[] chunks = {"ER23R5-1","A1B2C3-24","A1B2C3-2","6THF76-143","KL999H-23","JUSNWW-2","YH65SD-4","LA89DH-8","7UUUYU-3"};
          ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream("testing/currentInfo.txt"));
          outputStream.writeObject(chunks);
          //fim da cena falsa
        */

          //ler chunkIds do peer
          ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(Peer.CHUNKS_PATH + "/currentInfo.info"));

          String[] chunksNosInThisPeer = (String[])inputStream.readObject();
System.out.println("chunk index string []: " + Arrays.toString(chunksNosInThisPeer));

          //procurar chunkNo nos chunkNos deste peer
          List<String> chunkList = Arrays.asList(chunksNosInThisPeer);

          String[] fileNameSeparated = fileName.split("-");
          String fileId = fileNameSeparated[0];
          String chunkNo = fileNameSeparated[1];

          for (int i = 0;i < chunkList.size() ; i++ ) {
            String [] chunkInfo = chunkList.get(i).split("-");
            String thisFileId = chunkInfo[1];
            String thisChunkNo = chunkInfo[2];

            if(thisFileId == fileId){
              if(thisChunkNo == chunkNo){
                chunkIndex = i;
                break;
              }
            }
          }

      }
      catch(Exception e){
          System.out.println("ControlChannelListener > getChunkIndex: " + e);
      }
System.out.println("chunk index: " + chunkIndex);
      return chunkIndex;
  }

}
