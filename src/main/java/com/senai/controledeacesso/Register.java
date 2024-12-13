package com.senai.controledeacesso;

public class Register {
    int ID;
    String name;
    int phoneNumber;
    String email;
    String image;
    accessRegister accessRegister;

    Register(int ID, int accessId, String name, int phoneNumber, String email) {
        this.ID = ID;
        this.accessRegister.accessId = accessId;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.image = "-";
    }

    public String toString() {
        return String.format(
                "| %-5s | %-15s | %-20s | %-10s | %-10s | %-6s | %-10s |\n" +
                        "| %-5d | %-15s | %-20s | %-10s | %-10s | %-6d | %-10d |",
                "ID", "NOME", "NÚMERO DE TELEFONE", "EMAIL", "IMAGE", "ATRASOS", "ID ACESSO",
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
