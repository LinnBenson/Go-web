package io.todu.goweb;

import android.app.*;
import android.widget.*;
import org.mozilla.geckoview.*;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import java.util.*;

/** Native dialogs for web forms; website code receives the actual user choice. */
final class BrowserPrompts implements PromptDelegate {
    private final Activity activity;
    private final Set<AlertDialog> active=new HashSet<>();
    void dismissAll(){for(AlertDialog dialog:new ArrayList<>(active))dialog.cancel();active.clear();}
    BrowserPrompts(Activity activity) { this.activity=activity; }
    private AlertDialog.Builder dialog(BasePrompt p) { return new AlertDialog.Builder(activity).setTitle(p.title); }
    private void show(AlertDialog.Builder builder, BasePrompt p, GeckoResult<PromptResponse> result) {
        if(activity.isFinishing()||activity.isDestroyed()){if(!p.isComplete())result.complete(p.dismiss());return;}
        AlertDialog d=builder.setOnCancelListener(v -> {if(!p.isComplete())result.complete(p.dismiss());}).create();
        p.setDelegate(new PromptInstanceDelegate(){@Override public void onPromptDismiss(BasePrompt prompt){d.dismiss();}});
        d.setOnDismissListener(v->active.remove(d));active.add(d);d.show();
    }
    @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s,FilePrompt prompt){
        return ((MainActivity)activity).fileUi().choose(prompt);
    }
    @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s,AlertPrompt p) {
        GeckoResult<PromptResponse> r=new GeckoResult<>();show(dialog(p).setMessage(p.message).setPositiveButton("确定",(d,w)->r.complete(p.dismiss())),p,r);return r;
    }
    @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s,ButtonPrompt p) {
        GeckoResult<PromptResponse> r=new GeckoResult<>();show(dialog(p).setMessage(p.message).setPositiveButton("确定",(d,w)->r.complete(p.confirm(ButtonPrompt.Type.POSITIVE))).setNegativeButton("取消",(d,w)->r.complete(p.confirm(ButtonPrompt.Type.NEGATIVE))),p,r);return r;
    }
    @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s,TextPrompt p) {
        GeckoResult<PromptResponse> r=new GeckoResult<>();EditText input=new EditText(activity);input.setText(p.defaultValue);
        show(dialog(p).setMessage(p.message).setView(input).setPositiveButton("确定",(d,w)->r.complete(p.confirm(input.getText().toString()))).setNegativeButton("取消",(d,w)->r.complete(p.dismiss())),p,r);return r;
    }
    private void flatten(ChoicePrompt.Choice[] source,List<ChoicePrompt.Choice> out){for(ChoicePrompt.Choice c:source){if(c.items!=null)flatten(c.items,out);else if(!c.separator)out.add(c);}}
    @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s,ChoicePrompt p) {
        GeckoResult<PromptResponse> r=new GeckoResult<>();List<ChoicePrompt.Choice> choices=new ArrayList<>();flatten(p.choices,choices);
        String[] labels=new String[choices.size()];boolean[] selected=new boolean[choices.size()];int checked=-1;
        for(int i=0;i<labels.length;i++){labels[i]=choices.get(i).label;selected[i]=choices.get(i).selected;if(selected[i])checked=i;}
        AlertDialog.Builder b=dialog(p).setNegativeButton("取消",(d,w)->r.complete(p.dismiss()));
        if(p.type==ChoicePrompt.Type.MULTIPLE){
            b.setMultiChoiceItems(labels,selected,(d,i,v)->{if(choices.get(i).disabled){((AlertDialog)d).getListView().setItemChecked(i,selected[i]);return;}selected[i]=v;});
            b.setPositiveButton("确定",(d,w)->{List<String> ids=new ArrayList<>();for(int i=0;i<selected.length;i++)if(selected[i])ids.add(choices.get(i).id);r.complete(p.confirm(ids.toArray(new String[0])));});
        }else b.setSingleChoiceItems(labels,checked,(d,i)->{if(!choices.get(i).disabled){r.complete(p.confirm(choices.get(i).id));d.dismiss();}});
        show(b,p,r);return r;
    }
    @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession s,RepostConfirmPrompt p){
        GeckoResult<PromptResponse> r=new GeckoResult<>();show(dialog(p).setMessage("重新提交表单？").setPositiveButton("继续",(d,w)->r.complete(p.confirm(AllowOrDeny.ALLOW))).setNegativeButton("取消",(d,w)->r.complete(p.confirm(AllowOrDeny.DENY))),p,r);return r;
    }
}
