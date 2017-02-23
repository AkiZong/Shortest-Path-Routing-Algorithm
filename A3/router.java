import javax.xml.crypto.Data;
import java.io.*;
import java.net.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.*;
import java.nio.*;

public class router {

    static int NBR_Router = 5, routerID, nsePort, routerPort, num_Links;
    static InetAddress nseHost;
    static byte[] rcv_cdb;
    static DatagramSocket routerSocket;
    static Writer routerWriter;
    static circuit_DB circuitDB = new circuit_DB();
    static ArrayList<pkt_HELLO> send_pktHELLO = new ArrayList<pkt_HELLO>();
    static ArrayList<pkt_LSPDU> send_pktLSPDU_initial = new ArrayList<pkt_LSPDU>();
    static ArrayList<pkt_LSPDU> send_pktLSPDU_passive = new ArrayList<pkt_LSPDU>();
    static ArrayList<pkt_LSPDU> rcv_pktLSPDU = new ArrayList<pkt_LSPDU>();
    static ArrayList<pkt_INIT> send_pktINIT = new ArrayList<pkt_INIT>();
    static ArrayList<pkt_HELLO> rcv_pktHELLO = new ArrayList<pkt_HELLO>();
    static ArrayList<top_element> topologyData = new ArrayList<top_element>();
    static ArrayList<neighbor> neighbors = new ArrayList<neighbor>();

    static int[] nbr_link = new int [5];
    static int[] nextHops = new int [5];
    static int[] dist = new int[5];
    static int[] previous = new int[5];
    static PriorityQueue<int[]> queue = new PriorityQueue<int[]>(10, new Comparator<int[]>() {
        public int compare(int[] entry1, int[] entry2){
            return entry1[1] - entry2[1];

        }
    });
    ArrayList<int[]> tempQueue = new ArrayList<int[]>();



    // constructor
    router () {}


    // main function
    public static void main(String argv[]) throws Exception {
        try{
            router r = new router();
            r.Initialization(argv);
            System.out.println("router " + argv[0] + " is sending INIT Packet...");
            r.SendInit();
            System.out.println("router " + argv[0] + " is receiving Circuit Database...");
            r.ReceiveCDB();
            r.ParseCDB();
            System.out.println("router " + argv[0] + " is sending Hello Packets...");
            r.SendHello();
            System.out.println("router " + argv[0] + " is sending LSPDU Packets...");
            r.SendLSPDU_Initial();
        }catch(Exception e) {
            System.err.println(e);
        }
    }


    // Initialization: check length of args, pass values and create log file
    public void Initialization (String argv[]) throws Exception {
        try {
            if (argv.length != 4) {
                throw new IllegalArgumentException(
                        "ERROR: there should be 4 args <router_id> <nse_host> <nse_port> <router_port>");
            } else {
                // pass values
                routerID = Integer.parseInt(argv[0]);
                nseHost = InetAddress.getByName(argv[1]);
                nsePort = Integer.parseInt(argv[2]);
                routerPort = Integer.parseInt(argv[3]);
                routerSocket = new DatagramSocket(Integer.parseInt(argv[3]));
                // create router(id).log file
                System.out.println("Creating log file" + argv[0]);
                routerWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("router" + argv[0] + ".log"), "utf-8"));
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }


    // each router sends an INIT packet to the Network State Emulator
    public void SendInit() throws Exception {
        try{
            // create a pkt_INIT to store the INIT info and add the INIT to pktINIT
            pkt_INIT tempINIT = new pkt_INIT(routerID);
            send_pktINIT.add(tempINIT);
            // create an INIT buffer
            ByteBuffer bufferINIT = pkt_INIT.Generate_INIT_Buffer(tempINIT.router_id);
            // send the buffer to NSE
            SendByteBuffer(bufferINIT); // helper function #1
            // write message to log file
            String message = "R" + routerID + " sends an INIT: router_id " + routerID + "\n" + "\n";
            WriteToLog(message);
        }catch(Exception e){
            System.err.println("failed to send INIT");
            System.exit(1);
        }
    }


    //Receive circuit database
    public void ReceiveCDB() throws Exception {
        try{
            byte[] cdbData = new byte[1024];
            DatagramPacket cdbPacket = new DatagramPacket(cdbData, cdbData.length);
            routerSocket.receive(cdbPacket);
            rcv_cdb = cdbPacket.getData();
        }catch(Exception e){
            System.err.println("failed to receive circuit database.");
            System.exit(1);
        }
    }


    //Parse the received circuit database and store the information in our Link State Database.
    //We also need to write to our log files since the Link State Database changes.
    public void ParseCDB() throws Exception {
        try{
            ByteBuffer buffer = ByteBuffer.wrap(rcv_cdb);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            num_Links = buffer.getInt();
            circuitDB.nbr_link = num_Links;
            // write message to log file
            String message = "R" + routerID + " receives a CIRCUIT_DB: nbr_link " + num_Links + "\n" + "\n";
            WriteToLog(message);

            for(int i = 0; i < num_Links ; i++){
                // in circuitDB, create a new linkcost to store info from NSE
                int l = buffer.getInt();
                int c = buffer.getInt();
                circuitDB.linkcost[i] = new link_cost(l,c);
                top_element tempTop = new top_element(routerID,l,c);
                topologyData.add(tempTop);
                GetNeighbours();
                Dijkstra (routerID);
                FindNextHops();
                //PrintTopology();
                //PrintRIB();
            }
            PrintTopology();
            PrintRIB();
        }catch(Exception e){
            System.err.println("failed to parse circuit database.");
        }
    }


    //Send hello packets to neighbours
    public void SendHello() throws Exception{
        // before we send hello, we need to create a thread to receive hello
        Thread t_receive_HELLO = new Thread(new ReceiveHello());
        t_receive_HELLO.start();
        try{
            for(int i = 0; i < num_Links; i++){
                int r = routerID;
                int l = topologyData.get(i).link_id;
                pkt_HELLO tempHello = new pkt_HELLO(r,l);
                send_pktHELLO.add(tempHello);
                ByteBuffer buffer = pkt_HELLO.Generate_HELLO_Buffer(r, l);
                SendByteBuffer(buffer);
            }
            t_receive_HELLO.join();
        }catch(Exception e){
            System.err.println("failed to send hello packets.");
            System.exit(1);
        }
    }


    // receive Hello packets from neighbors
    private class ReceiveHello implements Runnable {
        @Override
        public void run(){
            for(int i = 0; i < num_Links; i++){
                try{
                    DatagramPacket helloPacket = ReceiveDatagramPacket(); // helper function #2
                    pkt_HELLO tempHELLO = pkt_HELLO.Parse_pkt_HELLO(helloPacket);
                    rcv_pktHELLO.add(tempHELLO);
                    System.out.println("Received Hello Packet from router " + tempHELLO.router_id);
                    // write to log file
                    String message = "R" + routerID + " receives a HELLO: router_id " +
                            tempHELLO.router_id + " link_id " + tempHELLO.link_id + "\n" + "\n";
                    WriteToLog(message); // helper function #3
                }catch(Exception e){
                    System.err.println("failed to receive circuit database");
                    System.exit(1);
                }
            }
        }
    }


    // send initial LSPDU to neighbours
    public void SendLSPDU_Initial () throws Exception {
        // before we send LSPDU, we need to create a new thread to receive LSPDU from neighbors
        Thread t_receiveLSPDU = new Thread(new ReceiveLSPDU());
        t_receiveLSPDU.start();
        int topSize = topologyData.size();
        try{
            for(int i = 0; i < num_Links; i++){
                for(int j = 0; j < topSize; j++){
                    int s = routerID;
                    int r = routerID;
                    int l = topologyData.get(j).link_id;
                    int c = topologyData.get(j).cost;
                    int v = topologyData.get(i).link_id;
                    // create a new pkt_LSPDU and add it to list
                    pkt_LSPDU tempLSPDU = new pkt_LSPDU(s,r,l,c,v);
                    send_pktLSPDU_initial.add(tempLSPDU);
                    // create a LSPDU BufferByte
                    ByteBuffer buffer = pkt_LSPDU.Generate_LSPDU_Buffer(s,r,l,c,v);
                    // send the buffer to NSE
                    SendByteBuffer(buffer); // helper function #1
                }
            }
        }catch(Exception e){
            System.err.println("failed to send LSPDU actively.");
            System.exit(1);
        }
    }


    //Send our LSPDU to our neighbours who send HELLO
    public void SendLSPDU_Normal (int viaFrom) throws Exception {
        int rcvHelloSize = rcv_pktHELLO.size();
        int topSize = topologyData.size();
        try{
            for(int i = 0; i < rcvHelloSize; i++){
                for(int j = 0; j < topSize; j++){
                    int s = routerID;
                    int r = topologyData.get(j).router_id;
                    int l = topologyData.get(j).link_id;
                    int c = topologyData.get(j).cost;
                    int v = rcv_pktHELLO.get(i).link_id;
                    // create a pkt_LSPDU and add to list
                    pkt_LSPDU tempLSPDU = new pkt_LSPDU(s,r,l,c,v);
                    send_pktLSPDU_passive.add(tempLSPDU);
                    // create a LSPDU ByteBuffer
                    ByteBuffer buffer = pkt_LSPDU.Generate_LSPDU_Buffer(s,r,l,c,v);
                    // send LSPDU to all its neighbors except the one who sends the LSPDU
                    if(rcv_pktHELLO.get(i).link_id != viaFrom){
                        // send buffer to NSE
                        SendByteBuffer(buffer);
                    }
                }
            }
        }catch(Exception e){
            System.err.println("failed to send LSPDU.");
            System.exit(1);
        }
    }


    //Receive LSPDU packets
    private class ReceiveLSPDU implements Runnable {
        @Override
        public void run () {
            while (true) {
                try {
                    byte[] linkStateData = new byte[1024];
                    DatagramPacket LSPDUPacket = new DatagramPacket(linkStateData, linkStateData.length);
                    routerSocket.receive(LSPDUPacket);
                    // create a new pkt_LSPDU to store received LSPDU and add to rcv_pktLSPDU
                    pkt_LSPDU tempLSPDU = pkt_LSPDU.Parse_pkt_LSPDU(LSPDUPacket);
                    rcv_pktLSPDU.add(tempLSPDU);
                    // get values from LSPDU and create a new top_element
                    int r_id = tempLSPDU.router_id;
                    int l_id = tempLSPDU.link_id;
                    int c = tempLSPDU.cost;
                    top_element tempTop = new top_element(r_id,l_id,c);
                    // if the received LSPDU is unique, then do next steps
                    if (!Is_LSPDU_Exist (r_id,l_id,c)) {
                        // write to log file
                        String message = "R" + routerID + " receives an LS PDU: sender " + tempLSPDU.sender
                                + ", router_id " + tempLSPDU.router_id + ", cost " + tempLSPDU.cost
                                + ", via " + tempLSPDU.via + "\n" + "\n";
                        WriteToLog(message);
                        System.out.println("router " + routerID + " received new LSPDU Information");
                        topologyData.add(tempTop);
                        GetNeighbours();
                        Dijkstra (routerID);
                        FindNextHops();
                        SendLSPDU_Normal(tempLSPDU.via);
                        PrintTopology();
                        PrintRIB();
                    }
                }catch(Exception e){
                    System.err.println("failed to receive LSPDU.");
                    System.exit(1);
                }
            }
        }
    }


    //Write the topology databse to the log file.
    public void PrintTopology() throws Exception {
        try{
            SortTopologyData(); // helper function #5
            Get_nbr_link(); // helper function #6
            String message = "# Topology database" + "\n";
            int topSize = topologyData.size();
            int j = 0;
            int position = 1;
            for (int i = 0; i < topSize; i++) {
                if(j == nbr_link[position - 1]){
                    j = 0;
                    position++;
                }
                if(j == 0){
                    while(true){
                        if(nbr_link[position - 1] == 0){
                            position++;
                        }
                        else{
                            message = message + "R" + routerID + " -> R" + position +
                                    " nbr link " + nbr_link[position - 1] + "\n";
                            break;
                        }
                    }
                }
                int r_id = topologyData.get(i).router_id;
                int l_id = topologyData.get(i).link_id;
                int c = topologyData.get(i).cost;
                message = message + "R" + routerID + " -> " + "R" +
                        r_id + " link " + l_id + " cost " + c + "\n";
                j++;
            }
            message = message + "\n";
            Arrays.fill(nbr_link, 0);
            System.out.println("Writing to Log file");
            WriteToLog(message);
        } catch (Exception e){
            System.err.println("failed to print topology database.");
            System.exit(1);
        }
    }


    // get all neighbors without duplicate
    public void GetNeighbours (){
        int topSize = topologyData.size();
        for(int i = 0; i < topSize; i++){
            for(int j = 0; j < topSize; j++){
                int r_id1 = topologyData.get(i).router_id;
                int r_id2 = topologyData.get(j).router_id;
                int l_id1 = topologyData.get(i).link_id;
                int l_id2 = topologyData.get(j).link_id;
                if ((r_id1 != r_id2) && (l_id1 == l_id2)) {
                    int l_id = topologyData.get(i).link_id;
                    int c = topologyData.get(i).cost;
                    neighbor tempNei = new neighbor(r_id2,r_id1,l_id,c);
                    // if the new neighbor is unique in the list, add to neighbors list
                    if(CheckUniqueNei(tempNei)){
                        neighbors.add(tempNei);
                    }
                    break;
                }
            }
        }
    }


    //Dijkstra's Algorithm to be run with the information from our Link State Database
    public void Dijkstra (int sourceRouter) throws Exception {
        try{
            dist[sourceRouter-1] = 0;
            for(int i = 0; i < NBR_Router; i++){
                if((i+1) != sourceRouter){
                    dist[i] = Integer.MAX_VALUE-i-500;
                    previous[i] = -1;
                }
                int[] queueEntry = new int[2];
                queueEntry[0] = i+1;
                queueEntry[1] = dist[i];
                queue.add(queueEntry);
            }
            while(queue.peek() != null){
                int[] u = new int[2];
                System.arraycopy(queue.poll(), 0, u, 0, u.length);
                int neiSize = neighbors.size();
                for(int j = 0; j < neiSize; j++){
                    if (neighbors.get(j).router1 == u[0]){
                        int alt = dist[u[0]-1] + neighbors.get(j).cost;
                        if(alt < dist[neighbors.get(j).router2 - 1]){
                            dist[neighbors.get(j).router2 - 1] = alt;
                            previous[neighbors.get(j).router2 - 1] = u[0];
                            changePriority();
                        }
                    }
                }
            }
        }catch(Exception e){
            System.err.println("failed to process dijkstra algorithm");
            System.exit(1);
        }
    }

    //Find the next hop given we know the shortest paths
    public void FindNextHops() throws Exception {
        try{
            int newprev;
            int oldprev;
            for(int i = 0; i < previous.length; i++){
                if(previous[i] == routerID){
                    nextHops[i] = i+1;
                }
                else if(previous[i] == 0){
                    nextHops[i] = 0;
                }
                else if(previous[i] == -1){
                    nextHops[i] = -1;
                }
                else{
                    newprev = previous[i];
                    oldprev = previous[i];
                    while(newprev != routerID){
                        newprev = previous[oldprev - 1];
                        if(newprev == routerID){
                            break;
                        }
                        oldprev = newprev;
                    }
                    nextHops[i] = oldprev;
                }
            }
        } catch (Exception e){
            System.err.println("failed to find next hops");
            System.exit(1);
        }
    }


    public void changePriority() {
        try{
            while(queue.peek() != null){
                int[] temp = new int[2];
                temp[0] = queue.peek()[0];
                temp[1] = queue.peek()[1];
                tempQueue.add(temp);
                queue.poll();
            }
            for(int j = 0; j < tempQueue.size(); j++){
                int[] temp2 = new int[2];
                temp2[0] = tempQueue.get(j)[0];
                temp2[1] = tempQueue.get(j)[1];
                queue.add(temp2);
            }
        }catch (Exception e){
            System.err.println("failed to change priority.");
            System.exit(1);
        }
    }


    //Write the Routing Information Base to log file.
    public void PrintRIB() throws Exception {
        try{
            String message = "# RIB" + "\n";
            for(int i = 0; i < NBR_Router; i++){
                message = message + "R" + routerID + " -> " + "R" + (i+1) + " -> ";
                if(nextHops[i] == -1){
                    message = message + "INF";
                }
                else if(nextHops[i] == 0){
                    message = message + "Local";
                }
                else{
                    message = message + "R" + nextHops[i];
                }
                message = message + "," + dist[i] + "\n";
            }
            message = message + "\n";
            System.out.println("Writing to log file");
            WriteToLog(message);
        } catch (Exception e){
            System.err.println("failed to print RIB.");
            System.exit(1);
        }
    }


    // ---------------------------- Helper function # 1 ----------------------------
    // transfer the buffer to data and send data to NSE
    public void SendByteBuffer (ByteBuffer bb) throws Exception {
        byte[] sendData = new byte[1024];
        sendData = bb.array();
        DatagramPacket sendPacket = new DatagramPacket(sendData,
                sendData.length, nseHost, nsePort);
        routerSocket.send(sendPacket);
    }


    // ---------------------------- Helper function # 2 ----------------------------
    public DatagramPacket ReceiveDatagramPacket () throws Exception {
        byte[] rcvData = new byte[1024];
        DatagramPacket rcvPacket = new DatagramPacket(rcvData, rcvData.length);
        routerSocket.receive(rcvPacket);
        return rcvPacket;
    }


    // ---------------------------- Helper function # 3 ----------------------------
    public void WriteToLog (String s) throws Exception {
        routerWriter.write(s);
        routerWriter.flush();
    }


    // ---------------------------- Helper function # 4 ----------------------------
    // check if the coming LSPDU exists in our topology database or not
    // If exists, renturn true, otherwise return false
    // If it is unique, return true, otherwise return false
    private boolean Is_LSPDU_Exist (int r, int l, int c){
        // r is routerID, l is linkID, c is cost
        for(int i = 0; i < topologyData.size(); i++){
            if((r == topologyData.get(i).router_id) &&
                    (l == topologyData.get(i).link_id) &&
                    (c == topologyData.get(i).cost)){
                return true;
            }
        }
        return false;
    }


    // ---------------------------- Helper function # 5 ----------------------------
    //Sort the Link State Database
    public void SortTopologyData () throws Exception {
        Collections.sort(topologyData, new Comparator<top_element> () {
            public int compare(top_element t1, top_element t2) {
                return Integer.valueOf(t1.router_id).compareTo(t2.router_id);
            }
        });
    }


    // ---------------------------- Helper function # 6 ----------------------------
    //Find the number of links connected to this router.
    public void Get_nbr_link() throws Exception {
        int topSize = topologyData.size();
        for (int i = 0; i < topSize; i++) {
            int r_id = topologyData.get(i).router_id;
            nbr_link[r_id-1]++;
        }
    }


    // ---------------------------- Helper function # 7 ----------------------------
    // Check if the neighbor is unique in the list.
    // If yes, return true, otherwise, return false
    public boolean CheckUniqueNei (neighbor n){
        int neiSize = neighbors.size();
        for(int i = 0; i < neiSize; i++){
            if((neighbors.get(i).router1 == n.router1) &&
                    (neighbors.get(i).router2 == n.router2)){
                return false;
            }
        }
        return true;
    }


    // ---------------------------- Other Helper Classes ----------------------------

    // ---------- class for router and distance pair ----------
    static public class router_distance {
        int router_id;
        int distance;

        router_distance (int r, int d) {
            router_id = r;
            distance = d;
        }
    }

    // ---------- class for neighbor ----------
    static public class neighbor {
        int router1;
        int router2;
        int link_id;
        int cost;

        neighbor (int r1, int r2, int l, int c) {
            router1 = r1;
            router2 = r2;
            link_id = l;
            cost = c;
        }
    }


    // ---------- class for topology_element ----------
    static public class top_element {
        int router_id;
        int link_id;
        int cost;

        top_element (int r, int l, int c) {
            router_id = r;
            link_id = l;
            cost = c;
        }
    }


    // ---------- class for pkt_HELLO ----------
    static public class pkt_HELLO {
        int router_id; /* id of the router who sends the HELLO PDU */
        int link_id; /* id of the link through which it is sent */

        pkt_HELLO (int r, int l) {
            router_id = r;
            link_id = l;
        }

        public static pkt_HELLO Parse_pkt_HELLO (DatagramPacket pkt) throws Exception {
            ByteBuffer buffer = ByteBuffer.wrap(pkt.getData());
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int r = buffer.getInt();
            int l = buffer.getInt();
            pkt_HELLO my_pkt_HELLO = new pkt_HELLO(r, l);
            return my_pkt_HELLO;
        }

        public static ByteBuffer Generate_HELLO_Buffer (int r, int l) throws Exception {
            ByteBuffer my_HELLO_buffer = ByteBuffer.allocate(8);
            my_HELLO_buffer.order(ByteOrder.LITTLE_ENDIAN);
            my_HELLO_buffer.putInt(r);
            my_HELLO_buffer.putInt(l);
            return my_HELLO_buffer;
        }
    }

    // ---------- class for pkt_LSPDU ----------
    static public class pkt_LSPDU {
        int sender; /* sender of the LS PDU */
        int router_id; /* router id */
        int link_id; /* link id */
        int cost; /* cost of the link */
        int via; /* id of the link through which the LS PDU is sent */

        pkt_LSPDU (int s, int r, int l, int c, int v) {
            sender = s;
            router_id = r;
            link_id = l;
            cost = c;
            via = v;
        }

        public static pkt_LSPDU Parse_pkt_LSPDU (DatagramPacket pkt) throws Exception {
            ByteBuffer buffer = ByteBuffer.wrap(pkt.getData());
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int s = buffer.getInt();
            int r = buffer.getInt();
            int l = buffer.getInt();
            int c = buffer.getInt();
            int v = buffer.getInt();
            pkt_LSPDU my_pkt_LSPDU = new pkt_LSPDU(s,r,l,c,v);
            return my_pkt_LSPDU;
        }

        public static ByteBuffer Generate_LSPDU_Buffer (int s, int r, int l, int c, int v) throws Exception {
            ByteBuffer my_LSPDU_buffer = ByteBuffer.allocate(20);
            my_LSPDU_buffer.order(ByteOrder.LITTLE_ENDIAN);
            my_LSPDU_buffer.putInt(s);
            my_LSPDU_buffer.putInt(r);
            my_LSPDU_buffer.putInt(l);
            my_LSPDU_buffer.putInt(c);
            my_LSPDU_buffer.putInt(v);
            return my_LSPDU_buffer;
        }
    }

    // ---------- class for pkt_INIT ----------
    static public class pkt_INIT {
        int router_id; /* id of the router that send the INIT PDU */
        pkt_INIT (int r) { router_id = r;}

        public static ByteBuffer Generate_INIT_Buffer (int r) throws Exception {
            ByteBuffer my_INIT_buffer = ByteBuffer.allocate(4);
            my_INIT_buffer.order(ByteOrder.LITTLE_ENDIAN);
            my_INIT_buffer.putInt(r);
            return my_INIT_buffer;
        }
    }

    // ---------- class for link_cost ----------
    static public class link_cost {
        public int link; /* link id */
        public int cost; /* associated cost */

        link_cost (int l, int c) {
            link = l;
            cost = c;
        }
    }

    // ---------- class for circuit_DB ----------
    static public class circuit_DB {
        public int nbr_link; /* number of links attached to a router */
        public link_cost[] linkcost = new router.link_cost[NBR_Router];
        /* we assume that at most NBR_ROUTER links are attached to each router */
    }
}
