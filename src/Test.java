import psdi.server.MXServer;
import psdi.util.logging.MXLoggerFactory;

import java.net.URL;
import java.rmi.RemoteException;

public class Test {

    public static void main(String[] args) throws RemoteException {
        URL u = MXServer.getMXServer().getClass().getResource("/maximo.properties");
    }

}
