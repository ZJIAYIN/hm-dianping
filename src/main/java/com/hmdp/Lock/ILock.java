package com.hmdp.Lock;

public interface ILock {

    public boolean tryLock(long timeoutSec);

    public void unLock();

}


