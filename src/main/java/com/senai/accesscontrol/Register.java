package com.senai.accesscontrol;

public class Register {
    int ID;
    String name;
    String phoneNumber;
    String email;
    String image;
    AccessRegister accessRegister;

    Register(int ID, String name, String phoneNumber, String email) {
        this.ID = ID;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.image = "-";
    }

    public String toString() {
        return String.format(
                "| %-5s | %-15s | %-20s | %-10s | %-10s | %-6s | %-10s |" +
                ID, name, phoneNumber, email, image, accessRegister.delays, accessRegister.accessId
        );
    }

    public void showAccessRegisters(){
        if (accessRegister.accessesArray.size() == 0){
            System.out.println("Você não tem registros de acesso");
        }
        for (int i = 0; i < accessRegister.accessesArray.size(); i++) {
            System.out.println(accessRegister.accessesArray.get(i));
        }
    }
}
