package com.thorqol.shared;

import android.app.Dialog;
import android.content.Context;
import android.os.*;
import android.view.*;

/** One click per A release; stick repeats only after the initial hold delay. */
final class ControllerDialog extends Dialog {
    private View armed; private boolean held; private int direction; private long next;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable repeat=new Runnable(){public void run(){if(!isShowing()||!getWindow().getDecorView().hasWindowFocus()||direction==0)return;long now=SystemClock.uptimeMillis();if(now>=next){move(direction);next=now+150;}handler.postDelayed(this,20);}};
    ControllerDialog(Context c){super(c);requestWindowFeature(Window.FEATURE_NO_TITLE);}
    private void reset(){held=false;armed=null;direction=0;handler.removeCallbacks(repeat);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int key=event.getKeyCode();if(key==KeyEvent.KEYCODE_BUTTON_A||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){held=true;armed=getCurrentFocus();}
        if(event.getAction()==KeyEvent.ACTION_UP){View target=armed;boolean click=held&&!event.isCanceled()&&target!=null&&target==getCurrentFocus();held=false;armed=null;if(click&&target.isEnabled())target.performClick();}return true;}
        if(key==KeyEvent.KEYCODE_BUTTON_B){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())cancel();return true;}return super.dispatchKeyEvent(event);}
    private void move(int key){long now=SystemClock.uptimeMillis();super.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,key,0));super.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,key,0));}
    @Override public boolean onGenericMotionEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_MOVE||!e.isFromSource(InputDevice.SOURCE_JOYSTICK))return super.onGenericMotionEvent(e);float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y),m=Math.max(Math.abs(x),Math.abs(y));int value=direction;
        if(m<.35f)value=0;else if(m>=.55f)value=Math.abs(x)>Math.abs(y)?(x<0?21:22):(y<0?19:20);
        if(value!=direction){handler.removeCallbacks(repeat);direction=value;if(value!=0){move(value);next=SystemClock.uptimeMillis()+380;handler.postDelayed(repeat,20);}}return true;}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus)reset();}
    @Override protected void onStop(){reset();super.onStop();}
}
