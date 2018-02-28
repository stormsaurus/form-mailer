/**
 * Copyright 2008 James Teer
 */

package io.james.util;

import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.lang.StringUtils;

/**
 * A simple utility to allow emails to a predefined address.  Emails are limited in size and frequency.
 *
 * @author James Teer
 *
 */
//TODO empty constructor should read properties file
//TODO use logging instead of System.out
//TODO generalize by changing mail() return value to boolean, wrap it and return a string if needed
public class Mailer {

    private Properties _mail = null;

    private int _maxSizeAddress = 0;
    private int _maxSizeSubject = 0;
    private int _maxSizeMessage = 0;
    private int _maxPerMinute = 0;
    private int _maxPerDay = 0;
    private boolean _requiresPopToUseSmtp = false;
    private InternetAddress _to = null;
    private InternetAddress _defaultFrom = null;
    private InternetAddress _cc = null;
    private InternetAddress _bcc = null;

    private int _messageCountMinute = 0;
    private int _messageCountDay = 0;

    private Timer _timer = null;
    private int _minutesCounted = 0;

    private boolean _available = false;

    /**
     *
     */
    public Mailer(){
        this(null);
    }

    /**
     * @param properties the properties used to configure the Mailer.
     */
    public Mailer(Properties properties){
        _mail = properties;

        //*** parse non-string properties
        String temp = "";
        try{
            _maxSizeAddress = Integer.parseInt(_mail.getProperty("maxSizeAddress"));
            _maxSizeSubject = Integer.parseInt(_mail.getProperty("maxSizeSubject"));
            _maxSizeMessage = Integer.parseInt(_mail.getProperty("maxSizeMessage"));
            _maxPerMinute = Integer.parseInt(_mail.getProperty("maxPerMinute"));
            _maxPerDay = Integer.parseInt(_mail.getProperty("maxPerMinute"));

            _to = new InternetAddress( _mail.getProperty("to"), true );
            temp = StringUtils.defaultString(_mail.getProperty("cc"));
            if( !temp.equals("") ) _cc = new InternetAddress( temp, true );
            temp = StringUtils.defaultString(_mail.getProperty("bcc"));
            if( !temp.equals("") ) _bcc = new InternetAddress( temp, true );
            temp = StringUtils.defaultString(_mail.getProperty("defaultFrom"));
            if( !temp.equals("") ) _defaultFrom = new InternetAddress( temp, true );
            //*** setup minute timer
            _timer = new Timer();
            _timer.scheduleAtFixedRate(new TimerTask(){                                    //timer to reset message count every minute and every day
                public void run(){
                    _messageCountMinute = 0;
                    _minutesCounted++;
                    if( _minutesCounted>60*24 ) {                                        //saves us from using two timers
                        _minutesCounted=0;
                        _messageCountDay=0;
                    }
                }
            }, 0, 1000*60);
            _requiresPopToUseSmtp = Boolean.parseBoolean(_mail.getProperty("requiresPopToUseSmtp"));
            _available = true;
        } catch (NumberFormatException e){
            System.out.println(getClass().getSimpleName()+" could not parse properties.  One or more numeric values are malformed.");
            System.out.println(e);
        } catch (AddressException e) {
            System.out.println(getClass().getSimpleName()+" could not parse properties.  One or more addresses are malformed.");
            System.out.println(e);
        }

    }

    /**
     * @param mailerName the named recipient defined in initialization properties.  This is not an address.
     * @param from email address.
     * @param subject
     * @param message
     * @return message indicating if the mail was sent.
     */
    public String mail(String mailerName, String from, String subject, String message){

        System.out.println(mailerName+" "+from+" "+subject+" "+message);

        if( !_available ) return "Mailer has not been configured.";
        if( _messageCountMinute>_maxPerMinute ) return "There have been a flood of emails.  Please try again in a minute.";
        if( _messageCountDay>_maxPerDay ) return "There have been a flood of emails.  Please try again in 24 hours.";
        _messageCountMinute++;
        _messageCountDay++;

        boolean success = false;

        //*** make sure data is safe to send
        mailerName = this.cleanupString(mailerName, 255);
        from = this.cleanupString(from, _maxSizeAddress);
        subject = this.cleanupString(from, _maxSizeSubject);
        message = this.cleanupString(from, _maxSizeMessage);

        //*** check that they know the mailer name
        if( !_mail.getProperty("name").equals(mailerName) )
            return "The name "+mailerName+" is not a valid mailer.";

        //*** this is safe email, let's go

        Store store = null;
        Folder folder = null;
        Transport transport = null;
        Properties sessionProperties = new Properties();
        sessionProperties.put("mail.smtp.host", _mail.getProperty("smtpHost"));
        sessionProperties.put("mail.smtp.auth", "true");
        Session session = Session.getInstance(sessionProperties, null);
        session.setDebug(false);
        try{

            if( _requiresPopToUseSmtp ){
                store = session.getStore("pop3");
                store.connect(_mail.getProperty("popHost"), _mail.getProperty("popUsername"), _mail.getProperty("popPassword"));
                folder = store.getFolder("INBOX");
                folder.open(Folder.READ_ONLY);
            }

            MimeMessage mimeMessage = null;
            mimeMessage = new MimeMessage(session);
            mimeMessage.addRecipient(Message.RecipientType.TO, _to );
            if( _cc!=null )
                mimeMessage.addRecipient(Message.RecipientType.CC, _cc );
            if( _bcc!=null )
                mimeMessage.addRecipient(Message.RecipientType.BCC, _bcc );
            if( _defaultFrom!=null )
                mimeMessage.setFrom( _defaultFrom );
            if( !from.equals("") )
                mimeMessage.setFrom( new InternetAddress(from, true) );
            mimeMessage.setSubject(subject);
            mimeMessage.setText(message);
            mimeMessage.saveChanges();

            transport = session.getTransport("smtp");
            transport.connect(_mail.getProperty("smtpHost"), _mail.getProperty("smtpUsername"), _mail.getProperty("smtpPassword"));
            transport.sendMessage(mimeMessage, mimeMessage.getAllRecipients());
            success = true;
        } catch (MessagingException me){
            System.out.println(getClass().getSimpleName()+" exception mailing message.");
            System.out.println(me);
        } finally {
            if (store!=null) try{store.close();} catch(MessagingException e) {}
            if (folder!=null) try{folder.close(false);} catch(MessagingException e) {}
            if (transport!=null) try{transport.close();} catch(MessagingException e) {}
        }

        if( success )
            return "Ok";
        else
            return "Failed to send mail.";

    }

    private String cleanupString(String str, int maxLength){
        str = StringUtils.defaultString(str);
        if( str.length()> maxLength )
            str = str.substring(0, maxLength-1);
        return str;
    }
}
