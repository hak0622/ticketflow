package studying.blog.fault;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Test-only: real commit succeeds, then JDBC caller receives a synthetic connection exception. */
@Configuration(proxyBeanMethods=false)
@Profile("payment-saga-fault")
public class PaymentCommitFaultProbe {
    static Path root(){ return Path.of(System.getProperty("fault.commit.root", "/fault")); }
    static final String INSTANCE=System.getenv().getOrDefault("FAULT_INSTANCE", "test");
    static final ThreadLocal<String> FAILED=new ThreadLocal<>();
    static synchronized void event(String key,String phase,String detail) throws Exception {
        Files.writeString(root().resolve("commit-"+INSTANCE+".tsv"),System.currentTimeMillis()+"\t"+key+"\t"+phase+"\t"+detail+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    @Bean static BeanPostProcessor commitProbe() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(bean instanceof org.springframework.transaction.PlatformTransactionManager manager) {
                    return Proxy.newProxyInstance(manager.getClass().getClassLoader(),
                            new Class[]{org.springframework.transaction.PlatformTransactionManager.class, ConfigurableTransactionManager.class},
                            (proxy, method, args) -> {
                                try { return invoke(manager,method,args); }
                                catch(Throwable error) {
                                    String key=FAILED.get();
                                    if(method.getName().equals("commit") && key!=null) {
                                        event(key,"spring_commit_failure",error.getClass().getName());FAILED.remove();
                                    }
                                    throw error;
                                }
                            });
                }
                if(!(bean instanceof DataSource source))return bean;
                return new DelegatingDataSource(source){
                    @Override public Connection getConnection() throws SQLException{return connection(super.getConnection());}
                    @Override public Connection getConnection(String user,String password)throws SQLException{return connection(super.getConnection(user,password));}
                };
            }
        };
    }
    static Connection connection(Connection original) {
        Map<String,Long> completed=new HashMap<>();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("prepareStatement") && args[0] instanceof String sql) {
                PreparedStatement stmt=(PreparedStatement)invoke(original,method,args);
                Map<Integer,Object> bindings=new HashMap<>();
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("clearParameters"))bindings.clear();
                    if(m.getName().startsWith("set") && a!=null && a.length>=2 && a[0] instanceof Integer index)bindings.put(index,m.getName().equals("setNull")?null:a[1]);
                    Object result=invoke(stmt,m,a);
                    if(m.getName().startsWith("execute") && stmt.getUpdateCount()>0) {
                        track(sql,bindings,completed);
                        Path arm=root().resolve("commit-target-"+INSTANCE+".arm");
                        if(Files.exists(arm))event(Files.readString(arm).trim().split(",")[2],"sql", "connection="+System.identityHashCode(original)+" "+sql+" "+bindings);
                    }
                    return result;
                });
            }
            if(name.equals("commit")) {
                Path arm=root().resolve("commit-target-"+INSTANCE+".arm");
                if(Files.exists(arm)) {
                    String[] target=Files.readString(arm).trim().split(",");
                    boolean cancel=target.length>3 && target[3].equals("CANCEL");
                    boolean matches=cancel ? Objects.equals(completed.get("canceled"),Long.valueOf(target[0])) && Objects.equals(completed.get("outboxPayment"),Long.valueOf(target[0])) && Objects.equals(completed.get("outboxBooking"),Long.valueOf(target[1])) : Objects.equals(completed.get("payment"),Long.valueOf(target[0])) && Objects.equals(completed.get("booking"),Long.valueOf(target[1]));
                    if(matches) {
                        Path claimed=root().resolve(target[2]+"-commit.claimed");
                        boolean claim=true;try{Files.createFile(claimed);}catch(FileAlreadyExistsException already){claim=false;}
                        if(claim) {
                            Object result=invoke(original,method,args); // Real JDBC/MySQL commit, not a mocked result.
                            completed.clear();
                            event(target[2],"real_commit_returned","connection="+System.identityHashCode(original)+",payment="+target[0]+",booking="+target[1]);
                            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
                            while(!Files.exists(root().resolve(target[2]+"-commit.resume"))) {
                                if(System.nanoTime()>end)throw new IllegalStateException("commit gate timeout");
                                Thread.sleep(10);
                            }
                            FAILED.set(target[2]);event(target[2],"jdbc_exception","SQLRecoverableException:08006");
                            throw new SQLRecoverableException("Test: real commit succeeded; ACK unavailable at JDBC boundary","08006");
                        }
                    }
                }
                Object result=invoke(original,method,args);completed.clear();return result;
            }
            if(name.equals("rollback") || name.equals("close") || name.equals("setAutoCommit"))completed.clear();
            if(name.equals("unwrap") && args[0]==Connection.class)return proxy;
            return invoke(original,method,args);
        });
    }
    static void track(String sql,Map<Integer,Object> bindings,Map<String,Long> completed) throws Exception {
        Matcher match=Pattern.compile("update (payment|booking) set (.*?) where id=\\?.*",Pattern.CASE_INSENSITIVE).matcher(sql.replace("`","").trim());
        if(!match.matches()) {
            Matcher insert=Pattern.compile("insert into payment_compensation_outbox \\((.*?)\\) values .*",Pattern.CASE_INSENSITIVE).matcher(sql.replace("`", "").trim());
            if(insert.matches()) {
                String[] columns=insert.group(1).split(",");Map<String,Object> row=new HashMap<>();
                for(int i=0;i<columns.length;i++)row.put(columns[i].trim(),bindings.get(i+1));
                if("payment.compensation".equals(row.get("event_type")) && "PENDING".equals(row.get("status"))) {
                    var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree((String)row.get("payload"));
                    if(payload.hasNonNull("paymentId") && payload.hasNonNull("bookingId") && payload.hasNonNull("concertId") && payload.hasNonNull("userId")) {
                        completed.put("outboxPayment",payload.get("paymentId").asLong());completed.put("outboxBooking",payload.get("bookingId").asLong());
                    }
                }
            }
            return;
        }
        String[] assignments=match.group(2).split(",");
        for(int i=0;i<assignments.length;i++)if(assignments[i].trim().startsWith("status=")) {
            Object state=bindings.get(i+1),id=bindings.get(assignments.length+1);
            if(id instanceof Number number && "CANCELED".equals(state))completed.put("canceled",number.longValue());
            if(id instanceof Number number && ("COMPLETED".equals(state)||"CONFIRMED".equals(state)))completed.put(match.group(1).toLowerCase(),number.longValue());
        }
    }
}
