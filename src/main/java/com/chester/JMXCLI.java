package com.chester;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static java.lang.management.ManagementFactory.THREAD_MXBEAN_NAME;
import static java.lang.management.ManagementFactory.getThreadMXBean;
import static java.lang.management.ManagementFactory.newPlatformMXBeanProxy;

/**
 * Main JMX command line client.
 */
public class JMXCLI {
	private class Attribute {
		private static final String COMPOSITE_ATTRIBUTE_DELIMITER = ".";
		private String attribute;
		private MBeanServerConnection connection;
		ObjectName obj;

		boolean isCompositeAttribute() {
			return attribute.contains(COMPOSITE_ATTRIBUTE_DELIMITER);
		}

		Attribute(MBeanServerConnection connection, ObjectName obj, String attribute) {
			this.attribute = attribute;
			this.obj = obj;
			this.connection = connection;
		}

		String get() throws InstanceNotFoundException,ReflectionException,AttributeNotFoundException,MBeanException {
			Object value;

			try {

				if (isCompositeAttribute()) {
					String[] compositeAttribute = attribute.split("\\" + COMPOSITE_ATTRIBUTE_DELIMITER);
					Object rawAttributeValue = connection.getAttribute(obj, compositeAttribute[0]);

					if (rawAttributeValue instanceof CompositeDataSupport) {
						value = ((CompositeDataSupport) rawAttributeValue).get(compositeAttribute[1]);

						if (value == null) {
							throw new AttributeNotFoundException();
						}
					} else {
						throw new RuntimeException("The following attribute is not a composite attribute : " + attribute);
					}

				} else {
					value = connection.getAttribute(obj, attribute);
				}

			} catch (AttributeNotFoundException e) {
				throw new RuntimeException("Attribute (" + attribute + ") not found on " + obj);
			} catch (Exception e) {
				throw new RuntimeException("Problem reading attribute (" + attribute + ") not found on " + obj, e);
			}

			if(ClassUtils.isPrimitiveOrWrapper(value.getClass())) {
				return value.toString();
			} else {
				return ReflectionToStringBuilder.toString(value);
			}
		}
	}

	/**
	 * Example of using the java.lang.management API to dump stack trace and to
	 * perform deadlock detection.
	 *
	 * @author Mandy Chung
	 * @version %% 12/22/05
	 */
	static class ThreadMonitor {
		private MBeanServerConnection server;

		private ThreadMXBean tmbean;

		private ObjectName objname;

		// default - JDK 6+ VM
		private String findDeadlocksMethodName = "findDeadlockedThreads";

		private boolean canDumpLocks = true;

		/**
		 * Constructs a ThreadMonitor object to get thread information in a remote
		 * JVM.
		 */
		public ThreadMonitor(MBeanServerConnection server) throws IOException {
			this.server = server;
			this.tmbean = newPlatformMXBeanProxy(server, THREAD_MXBEAN_NAME, ThreadMXBean.class);
			try {
				objname = new ObjectName(THREAD_MXBEAN_NAME);
			} catch (MalformedObjectNameException e) {
				// should not reach here
				InternalError ie = new InternalError(e.getMessage());
				ie.initCause(e);
				throw ie;
			}
			parseMBeanInfo();
		}

		/**
		 * Constructs a ThreadMonitor object to get thread information in the local
		 * JVM.
		 */
		public ThreadMonitor() {
			this.tmbean = getThreadMXBean();
		}

		/**
		 * Prints the thread dump information to System.out.
		 */
		public void threadDump() {
			if (canDumpLocks) {
				if (tmbean.isObjectMonitorUsageSupported() && tmbean.isSynchronizerUsageSupported()) {
					// Print lock info if both object monitor usage
					// and synchronizer usage are supported.
					// This sample code can be modified to handle if
					// either monitor usage or synchronizer usage is supported.
					dumpThreadInfoWithLocks();
				}
			} else {
				dumpThreadInfo();
			}
		}

		private void dumpThreadInfo() {
			System.out.println("Full Java thread dump");
			long[] tids = tmbean.getAllThreadIds();
			ThreadInfo[] tinfos = tmbean.getThreadInfo(tids, Integer.MAX_VALUE);
			for (ThreadInfo ti : tinfos) {
				printThreadInfo(ti);
			}
		}

		/**
		 * Prints the thread dump information with locks info to System.out.
		 */
		private void dumpThreadInfoWithLocks() {
			System.out.println("Full Java thread dump with locks info");

			ThreadInfo[] tinfos = tmbean.dumpAllThreads(true, true);
			for (ThreadInfo ti : tinfos) {
				printThreadInfo(ti);
				LockInfo[] syncs = ti.getLockedSynchronizers();
				printLockInfo(syncs);
			}
			System.out.println();
		}

		private final String INDENT = "    ";

		private void printThreadInfo(ThreadInfo ti) {
			// print thread information
			printThread(ti);

			// print stack trace with locks
			StackTraceElement[] stacktrace = ti.getStackTrace();
			MonitorInfo[] monitors = ti.getLockedMonitors();
			for (int i = 0; i < stacktrace.length; i++) {
				StackTraceElement ste = stacktrace[i];
				System.out.println(INDENT + "at " + ste.toString());
				for (MonitorInfo mi : monitors) {
					if (mi.getLockedStackDepth() == i) {
						System.out.println(INDENT + "  - locked " + mi);
					}
				}
			}
			System.out.println();
		}

		private void printThread(ThreadInfo ti) {
			StringBuilder sb = new StringBuilder("\"" + ti.getThreadName() + "\"" + " Id="
					+ ti.getThreadId() + " in " + ti.getThreadState());
			if (ti.getLockName() != null) {
				sb.append(" on lock=" + ti.getLockName());
			}
			if (ti.isSuspended()) {
				sb.append(" (suspended)");
			}
			if (ti.isInNative()) {
				sb.append(" (running in native)");
			}
			System.out.println(sb.toString());
			if (ti.getLockOwnerName() != null) {
				System.out.println(INDENT + " owned by " + ti.getLockOwnerName() + " Id="
						+ ti.getLockOwnerId());
			}
		}

		private void printMonitorInfo(ThreadInfo ti, MonitorInfo[] monitors) {
			System.out.println(INDENT + "Locked monitors: count = " + monitors.length);
			for (MonitorInfo mi : monitors) {
				System.out.println(INDENT + "  - " + mi + " locked at ");
				System.out.println(INDENT + "      " + mi.getLockedStackDepth() + " "
						+ mi.getLockedStackFrame());
			}
		}

		private void printLockInfo(LockInfo[] locks) {
			System.out.println(INDENT + "Locked synchronizers: count = " + locks.length);
			for (LockInfo li : locks) {
				System.out.println(INDENT + "  - " + li);
			}
			System.out.println();
		}

		/**
		 * Checks if any threads are deadlocked. If any, print the thread dump
		 * information.
		 */
		public boolean findDeadlock() {
			long[] tids;
			if (findDeadlocksMethodName.equals("findDeadlockedThreads")
					&& tmbean.isSynchronizerUsageSupported()) {
				tids = tmbean.findDeadlockedThreads();
				if (tids == null) {
					return false;
				}

				System.out.println("Deadlock found :-");
				ThreadInfo[] infos = tmbean.getThreadInfo(tids, true, true);
				for (ThreadInfo ti : infos) {
					printThreadInfo(ti);
					printLockInfo(ti.getLockedSynchronizers());
					System.out.println();
				}
			} else {
				tids = tmbean.findMonitorDeadlockedThreads();
				if (tids == null) {
					return false;
				}
				ThreadInfo[] infos = tmbean.getThreadInfo(tids, Integer.MAX_VALUE);
				for (ThreadInfo ti : infos) {
					// print thread information
					printThreadInfo(ti);
				}
			}

			return true;
		}

		private void parseMBeanInfo() throws IOException {
			try {
				MBeanOperationInfo[] mopis = server.getMBeanInfo(objname).getOperations();

				// look for findDeadlockedThreads operations;
				boolean found = false;
				for (MBeanOperationInfo op : mopis) {
					if (op.getName().equals(findDeadlocksMethodName)) {
						found = true;
						break;
					}
				}
				if (!found) {
					// if findDeadlockedThreads operation doesn't exist,
					// the target VM is running on JDK 5 and details about
					// synchronizers and locks cannot be dumped.
					findDeadlocksMethodName = "findMonitorDeadlockedThreads";
					canDumpLocks = false;
				}
			} catch (IntrospectionException e) {
				InternalError ie = new InternalError(e.getMessage());
				ie.initCause(e);
				throw ie;
			} catch (InstanceNotFoundException e) {
				InternalError ie = new InternalError(e.getMessage());
				ie.initCause(e);
				throw ie;
			} catch (ReflectionException e) {
				InternalError ie = new InternalError(e.getMessage());
				ie.initCause(e);
				throw ie;
			}
		}
	}


	@Option(name = "-auth", usage = "username:password of secured JMX Connection")
	private String auth;

    @Argument(required = true, index = 0, usage = "hostname:port of the jmx server, e.g. localhost:8090")
    private String hostPort;

    @Argument(required = false, index = 1, usage = "Name of the JMX object, e.g. com.mchange" +
			".v2.c3p0:type=PooledDataSource.* will return the first matching object")
    private String objectName;

    @Argument(required = false, index = 2, usage = "Attribute name of the JMX object, e.g. numBusyConnections")
    private String attributeName;

    @Argument(required = false, index = 3, usage = "Time to pause between runs in seconds]")
    private long pause = 10;

	@Argument(required = false, index = 4, usage = "How many times to iterate")
	private long runCount = 1;

	private JMXConnector jmxConnector;

    private boolean printheader = false;

    /**
     * Connect to the JMXServer
     * @return connector
     */
    private JMXConnector connect() {

        String[] hostAndPort = hostPort.split(":");
        if (hostAndPort.length != 2) throw new IllegalStateException("Could not parse hostname and port from " + hostPort);
        String parsedHost = hostAndPort[0];
        String parsedPort = hostAndPort[1];
               
        if (parsedHost == null || parsedHost == "") throw new IllegalArgumentException("Could not parse host");
        if (parsedPort == null || parsedPort == "") throw new IllegalArgumentException("Could not parse host");        

        try {

            if (jmxConnector == null) {               

                JMXServiceURL serviceURL = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + parsedHost + ":" + parsedPort + "/jmxrmi");

				HashMap<String, String[]> credentials = new HashMap<String, String[]>();

				if (StringUtils.isNotBlank(this.auth)) {
					String[] auth = StringUtils.split(this.auth,":",2);
					if(auth.length == 2) {
						credentials.put(JMXConnector.CREDENTIALS, new String[]{auth[0], auth[1]});
					}
				}

                if (runCount == 0 && printheader) {
                    System.out.println("Created connection with service URL: " + serviceURL);
                    System.out.println("Will execute until CTRL-C received");
                }

                jmxConnector = JMXConnectorFactory.connect(serviceURL, credentials);

            } else {
                return jmxConnector;
            }

        } catch (IOException e) {
            System.err.println("Could not connect via JMX " + parsedHost + ":" + parsedPort + "\n" + e);
        }
        return null;
    }   

    public void printObjectNames() {
        for (String name : getObjectNameList()) {
            System.out.println(name);
        }
    }

    public List<String> getObjectNameList() {
        List<String> names = new ArrayList<String>();        
        Set<ObjectInstance> beans;
        try {
            beans = jmxConnector.getMBeanServerConnection().queryMBeans(null, null);

            for (ObjectInstance instance : beans) {
                names.add(instance.getObjectName().toString());
            }
        } catch (IOException e) {
            System.err.println("IOExcepton " + e);
        } 
        return names;
    }

    public String nameExists(String name) {
        
        ObjectName oName = null;
        if (name != null) {
            oName = createJmxObject(name);
        }

        Set<ObjectInstance> beans;
        try {
            beans = jmxConnector.getMBeanServerConnection().queryMBeans(oName, null);

            for (ObjectInstance instance : beans) {
                return instance.getObjectName().toString();
            }

        } catch (IOException e) {
            System.err.println("IOExcepton " + e);
        } 

        return null;
    }

    private void closeConnection() {
        try {
            if (jmxConnector != null) {
                jmxConnector.close();
            }
        } catch (IOException e) {
            try {
                System.out.println("Could not close connection " + jmxConnector.getConnectionId());
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }



    public List<String[]> getAttributeList(String name) {
        List<String[]> attributes = new ArrayList<>();
        ObjectName oName = null;
        if (name != null) {
            oName = createJmxObject(name);
        }

        try {
            MBeanInfo info = jmxConnector.getMBeanServerConnection().getMBeanInfo(oName);
            for (MBeanAttributeInfo att : info.getAttributes()) {
                attributes.add(new String[]{att.getName(), att.getType(), att.getDescription()});
            }

        } catch (ReflectionException e) {
            System.err.println("ReflectionException " + e);
        } catch (IOException e) {
            System.err.println("IOExcepton " + e);
        } catch (InstanceNotFoundException e) {
            System.err.println("InstanceNotFoundException " + e);
        } catch (IntrospectionException e) {
            System.err.println("IntrospectionException " + e);
        } 
        return attributes;
    }

    public String getAttribute(String name, String attribute) {

        if (jmxConnector != null) {

            try {

                ObjectName obj = createJmxObject(name);
                MBeanServerConnection connection = jmxConnector.getMBeanServerConnection();
				Attribute attr = new Attribute(connection, obj, attribute);

                return attr.get();

            } catch (InstanceNotFoundException e) {
                System.err.println("InstanceNotFoundException " + e);
            } catch (ReflectionException e) {
                System.err.println("ReflectionException " + e);
            } catch (IOException e) {
                System.err.println("IOException " + e);
            } catch (AttributeNotFoundException e) {
                System.err.println("AttributeNotFoundException " + e);
            } catch (MBeanException e) {
                System.err.println("MBeanException " + e);
            } 
        } else {
            return "Could not create connection to host";
        }

        return "Not supported yet";
    }

    public ObjectName createJmxObject(String aName) {
        ObjectName oName = null;
        try {
            oName = new ObjectName(aName);
        } catch (MalformedObjectNameException e) {
            System.err.println("MalformedObjectNameException " + e);
        }
        return oName;
    }

    private String getObjectName() {
        return objectName;
    }
    
    private String getAttributeName() {        
        return attributeName;
    }
    
    private long getRunCount() {
        return runCount;
    }

	private List<String> findObjects(String objectNameToFind) {
		List<String> ret = new ArrayList<>();

		if(!objectNameToFind.contains("*")) {
			ret.add(objectNameToFind);
			return ret;
		}
		List<String> objectNames = getObjectNameList();
		for (String name : objectNames) {
			if (name.matches(objectNameToFind)) {
				ret.add(name);
			}
		}
		return ret;
	}

	private List<String> findAttributes(String object, String attributeNameToFind) {
		List<String> ret = new ArrayList<>();

		if(!attributeNameToFind.contains("*")) {
			ret.add(attributeNameToFind);
			return ret;
		}

		List<String[]> attributeList = getAttributeList(object);
		for (String[] name : attributeList) {
			if (name[0].matches(attributeNameToFind)) {
				ret.add(name[0]);
			}
		}
		return ret;
	}


    private long getPause() {        
        return pause * 1000;
    }     

    public static void main(String[] args) {

        JMXCLI client = new JMXCLI();
        CmdLineParser parser = new CmdLineParser(client);
        parser.setUsageWidth(80); 

        try {
            parser.parseArgument(args);            
            client.connect();


            if (StringUtils.isBlank(client.getObjectName())) {
                listObjects(client);
			} else if (client.getObjectName().equals("threads")) {
				listThreads(client);
			} else if (StringUtils.isBlank(client.getAttributeName())) {
				listAttributes(client);
			} else {
				getObject(client);
			}
            
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println(" [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            System.exit(1);
        } finally {
            client.closeConnection();
        }
    }

    private static void listObjects(JMXCLI client) {
		final List<String> nameList = client.getObjectNameList();

		if(nameList.isEmpty()) {
			System.out.println("Listing JMX objects for client returns nothing");
		}
        for (String objectName: nameList) {
            System.out.println(objectName);
        }
    }

	private static void listAttributes(JMXCLI client) {
		System.out.println(String.format("## JMX Attributes for %s", client.getObjectName()));
		if (client.getObjectName() != null) {

			for (String[] attributeName: client.getAttributeList(client.getObjectName())) {
				System.out.println(attributeName[0] + " [" + attributeName[1] + "] " + attributeName[2]);
			}
		} else {
			System.out.println("## You must specify an JMX objectname");
		}
	}

	private static void listThreads(JMXCLI client){

		ThreadMonitor monitor = null;
		try {
			monitor = new ThreadMonitor(client.jmxConnector.getMBeanServerConnection());
		} catch (IOException e) {
			e.printStackTrace();
		}
		monitor.threadDump();
	}

    private static void getObject(JMXCLI client) {


		HashMap<String,List<String>> attributes = new HashMap<>();

		for(String objectName: client.findObjects(client.getObjectName())) {
			attributes.put(objectName, client.findAttributes(objectName, client.getAttributeName()));
		}

        long runCount = 0;
        while ( client.getRunCount() == 0 || runCount < client.getRunCount() ) {

        	if (runCount++ > 0) {
				try {
					Thread.sleep(client.getPause());
					System.out.println(String.format("##### " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
							.format(new Date())));
				} catch (InterruptedException e) {
				}
			}

            for (String objectName: attributes.keySet()) {
				if (attributes.size()>1) {
					System.out.println("#### " + objectName);
				}
				for (String attribute: attributes.get(objectName) ) {
					System.out.println(String.format("%s=%s", attribute, client.getAttribute(objectName, attribute)));
				}
			}
        }
    }   
}
