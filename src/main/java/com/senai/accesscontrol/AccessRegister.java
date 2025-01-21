package com.senai.accesscontrol;

import java.util.ArrayList;

public class AccessRegister {
    int accessId;
    ArrayList<String> accessesArray = new ArrayList<>();
    String delays;

    public AccessRegister(int accessId) {
        this.accessId = accessId;
    }

    public void registerNewAccess(String accessRegister){
        accessesArray.add(accessRegister);
        delays += 1;
    }

}



