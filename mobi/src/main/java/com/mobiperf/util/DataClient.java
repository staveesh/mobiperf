package com.mobiperf.util;

import com.mobiperf.Logger;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;

public class DataClient implements Runnable {

    private static String SERVER_ADDRESS="10.0.0.4";
    private static int SERVER_PORT=7000;
    private String collectedResult;

    public DataClient(String result){
        collectedResult=result;
    }

    @Override
    public void run() {
       try{
        Socket clientSocket = new Socket(SERVER_ADDRESS,SERVER_PORT);
        PrintWriter out=new PrintWriter(clientSocket.getOutputStream(),true);
        out.println(collectedResult);
        Logger.i("Data Sent to Server");
       }
       catch (UnknownHostException e){
           e.printStackTrace();
       }
       catch (IOException e){
           e.printStackTrace();
       }
    }
}
