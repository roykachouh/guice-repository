/****************************************************************************\
 __AUTHOR........: Alexey Krylov
 __CREATED.......: 19.03.12
 __COPYRIGHT.....: Copyright (c) 2011 Jobdone 
 __VERSION.......: 1.0
 __DESCRIPTION...:
 ****************************************************************************/


package org.guice.repository.test;

import com.google.inject.Inject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RepoTestRunner.class)
public class JpaRepositoryTest {
/*===========================================[ STATIC VARIABLES ]=============*/
/*===========================================[ INSTANCE VARIABLES ]=========*/
/*===========================================[ CONSTRUCTORS ]===============*/
/*===========================================[ CLASS METHODS ]==============*/

//    @Inject
//    private CustomerRepository customerRepository;

    @Inject
    private AccountRepository accountRepository;


    @Test
    public void testRepo() throws Exception {
        List<Account> accounts = new LinkedList<Account>();
        int count = 10;
        for (int i = 0; i < count; i++) {
            accounts.add(new Account(UUID.randomUUID().toString(), String.valueOf(i)));
        }

        accountRepository.save(accounts);
        assertEquals(count, accountRepository.count());
        assertNotNull(accountRepository.findAccountByName(String.valueOf(1)));
        assertNotNull(accountRepository.findAccountByUuid(accounts.get(0).getUuid()));

//        accountRepository.save(new Account(UUID.randomUUID().toString()));
//        assertEquals(1, accountRepository.count());

//        accountRepository.deleteAll();
//        assertEquals(0, accountRepository.count());


//        accountRepository.storeInBatch(accounts);
//        assertEquals(10, accountRepository.count());

//        accountRepository.findAccountByUuid(UUID.randomUUID());
    }
}