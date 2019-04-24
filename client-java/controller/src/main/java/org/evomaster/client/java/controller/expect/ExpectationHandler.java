package org.evomaster.client.java.controller.expect;


import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 *  DSL (Domain Specific Language) for expectation management.
 *  The goal is to allow checks on the truth value, results, and contents
 *  of specific calls.
 *  If expectations are active, a failed condition will
 *  raise an exception.
 *  If expectations are not active, no exception will be raised regardless
 *  of the truth value of the condition.
 *
 *  WiP: A more finely grained mechanism for activation is planned.
 *  The goal is to allow activating and deactivating specific types of
 *  expectations.
 *
 */


public class ExpectationHandler implements AggregateExpectation, IndividualExpectation {

    /**
     *
     * @return a DSL object to handle expectation operations.
     */
    public static ExpectationHandler expectationHandler() {
        return new ExpectationHandler();
    }

    private ExpectationHandler(){}

    @Override
    public IndividualExpectation expect(){
        return this;
    }

    @Override
    public IndividualExpectation that(boolean active, boolean condition){
        if(!active) return this;
        if (!condition) throw new IllegalArgumentException("Failed Expectation Exception");
        return this;
    }


    @Override
    public IndividualExpectation that(boolean active, Method method, Object[] args){
        if (!active) return this;
        else {
            try{
                method.invoke(null, args);
            }
            catch (Exception e1){
                //TODO: handle this somehow?
            }
        }
        return this;
    }

}
