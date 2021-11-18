package org.slf4j;


import java.util.logging.Level;

public class JavaLogLogger implements Logger {

    java.util.logging.Logger logger;

    public JavaLogLogger(String name) {
        logger = java.util.logging.Logger.getLogger(name);
    }

    @Override
    public String getName() {
        return logger.getName();
    }

    @Override
    public boolean isTraceEnabled() {
        return logger.getLevel() == Level.FINER;
    }

    @Override
    public void trace(String msg) {
        logger.log(Level.FINER, msg);
    }

    @Override
    public void trace(String format, Object arg) {
        logger.log(Level.FINER, format, arg);
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        logger.log(Level.FINER, String.format(format, arg1, arg2));
    }

    @Override
    public void trace(String format, Object... arguments) {
        logger.log(Level.FINER, format, arguments);
    }

    @Override
    public void trace(String msg, Throwable t) {
        logger.log(Level.FINER, msg, t);
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return isTraceEnabled();
    }

    @Override
    public void trace(Marker marker, String msg) {
        trace(msg);
    }

    @Override
    public void trace(Marker marker, String format, Object arg) {
        trace(format, arg);
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        trace(format, arg1, arg2);
    }

    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        trace(format, argArray);
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        trace(msg, t);
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.getLevel() == Level.FINE;
    }

    @Override
    public void debug(String msg) {
        logger.log(Level.FINER, msg);
    }

    @Override
    public void debug(String format, Object arg) {
        logger.log(Level.FINER, format, arg);
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        debug(format, new Object[]{arg1, arg2});
    }

    @Override
    public void debug(String format, Object... arguments) {
        logger.log(Level.FINER, format, arguments);
    }

    @Override
    public void debug(String msg, Throwable t) {
        logger.log(Level.FINER, msg, t);
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return false;
    }

    @Override
    public void debug(Marker marker, String msg) {

    }

    @Override
    public void debug(Marker marker, String format, Object arg) {

    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {

    }

    @Override
    public void debug(Marker marker, String format, Object... arguments) {

    }

    @Override
    public void debug(Marker marker, String msg, Throwable t) {

    }

    @Override
    public boolean isInfoEnabled() {
        return logger.getLevel() == Level.INFO;
    }

    @Override
    public void info(String msg) {
        logger.log(Level.INFO, msg);
    }

    @Override
    public void info(String format, Object arg) {
        logger.log(Level.INFO, format, arg);
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        info(format, new Object[]{arg1, arg2});
    }

    @Override
    public void info(String format, Object... arguments) {
        logger.log(Level.INFO, format, arguments);
    }

    @Override
    public void info(String msg, Throwable t) {
        logger.log(Level.INFO, msg, t);
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return logger.getLevel() == Level.INFO;
    }

    @Override
    public void info(Marker marker, String msg) {

    }

    @Override
    public void info(Marker marker, String format, Object arg) {

    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {

    }

    @Override
    public void info(Marker marker, String format, Object... arguments) {

    }

    @Override
    public void info(Marker marker, String msg, Throwable t) {

    }

    @Override
    public boolean isWarnEnabled() {
        return logger.getLevel() == Level.WARNING;
    }

    @Override
    public void warn(String msg) {
        logger.log(Level.WARNING, msg);
    }

    @Override
    public void warn(String format, Object arg) {
        logger.log(Level.WARNING, format, arg);
    }

    @Override
    public void warn(String format, Object... arguments) {
        logger.log(Level.WARNING, String.format(format, arguments));
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        logger.log(Level.WARNING, String.format(format, arg1, arg2));
    }

    @Override
    public void warn(String msg, Throwable t) {
        logger.log(Level.WARNING, msg, t);
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return logger.getLevel() == Level.WARNING;
    }

    @Override
    public void warn(Marker marker, String msg) {

    }

    @Override
    public void warn(Marker marker, String format, Object arg) {

    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {

    }

    @Override
    public void warn(Marker marker, String format, Object... arguments) {

    }

    @Override
    public void warn(Marker marker, String msg, Throwable t) {

    }

    @Override
    public boolean isErrorEnabled() {
        return logger.getLevel() == Level.SEVERE;
    }

    @Override
    public void error(String msg) {
        logger.log(Level.SEVERE, msg);
    }

    @Override
    public void error(String format, Object arg) {
        logger.log(Level.SEVERE, String.format(format, arg));
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        logger.log(Level.SEVERE, String.format(format, arg1, arg2));
    }

    @Override
    public void error(String format, Object... arguments) {
        logger.log(Level.SEVERE, String.format(format, arguments));
    }

    @Override
    public void error(String msg, Throwable t) {
        logger.log(Level.SEVERE, msg, t);
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return logger.getLevel() == Level.SEVERE;
    }

    @Override
    public void error(Marker marker, String msg) {

    }

    @Override
    public void error(Marker marker, String format, Object arg) {

    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {

    }

    @Override
    public void error(Marker marker, String format, Object... arguments) {

    }

    @Override
    public void error(Marker marker, String msg, Throwable t) {

    }
}
