package io.todu.goweb;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import androidx.wear.watchface.complications.data.*;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** Static launcher shortcut: no browser initialization, network or periodic work. */
public final class OpenAppComplicationService extends ComplicationDataSourceService {
    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        try { listener.onComplicationData(data(request.getComplicationType(), true, ComplicationIcons.DRAWABLES[
            ComplicationIcons.selected(this, request.getComplicationInstanceId())])); }
        catch (android.os.RemoteException disconnected) { /* Watch face disconnected. */ }
    }
    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return data(type, false, R.drawable.ic_complication_go);
    }
    private ComplicationData data(ComplicationType type, boolean actionable, int drawable) {
        Icon icon=Icon.createWithResource(this,drawable);
        PlainComplicationText description=new PlainComplicationText.Builder("打开 Go web").build();
        PendingIntent tap=null;
        if(actionable){
            Intent launch=new Intent(this,MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            tap=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        }
        if(type==ComplicationType.MONOCHROMATIC_IMAGE){
            MonochromaticImage image=new MonochromaticImage.Builder(icon).setAmbientImage(icon).build();
            return new MonochromaticImageComplicationData.Builder(image,description).setTapAction(tap).build();
        }
        if(type==ComplicationType.SMALL_IMAGE){
            SmallImage image=new SmallImage.Builder(icon,SmallImageType.ICON).setAmbientImage(icon).build();
            return new SmallImageComplicationData.Builder(image,description).setTapAction(tap).build();
        }
        return null;
    }
}
