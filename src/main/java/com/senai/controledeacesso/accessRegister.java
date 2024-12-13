package com.senai.controledeacesso;

import java.util.ArrayList;

public class accessRegister {
    int accessId;
    ArrayList<String> accessesArray = new ArrayList<>();
    String delays;

    public accessRegister(int accessId) {
        this.accessId = accessId;
    }

    public void registerNewAccess(String accessRegister){
        accessesArray.add(accessRegister);
        delays += 1;
    }

}



